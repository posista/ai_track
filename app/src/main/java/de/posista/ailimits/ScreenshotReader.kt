package de.posista.ailimits

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal suspend fun readScreenshot(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Unsupported image URI")
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dimensions) }
    val width = dimensions.outWidth
    val height = dimensions.outHeight
    if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384) throw IOException("Invalid image dimensions")
    var sample = 1
    while (width.toLong() * height / sample / sample > 8_000_000L) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("Undecodable image")

    suspendCancellableCoroutine { continuation ->
        val recognizer = try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (e: Exception) {
            bitmap.recycle()
            continuation.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                recognizer.close()
                bitmap.recycle()
                if (continuation.isActive) {
                    val text = task.resultOrNull()
                    if (text != null) continuation.resume(text)
                    else continuation.resumeWithException(task.exception ?: IOException("Image recognition cancelled"))
                }
            }
        } catch (e: Exception) {
            recognizer.close()
            bitmap.recycle()
            continuation.resumeWithException(e)
        }
    }
}

private fun com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>.resultOrNull(): String? =
    if (isSuccessful) result.text.take(ResetParser.MAX_INPUT) else null
