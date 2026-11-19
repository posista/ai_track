package de.posista.ailimits

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class LimitApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, LimitDatabase::class.java, "limits.db").build()
    }
    val store by lazy { LimitStore(database) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val work by lazy { WorkManager.getInstance(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Limit-Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Meldungen zu voraussichtlichen Rücksetzzeiten" }
        )
        work.enqueueUniquePeriodicWork("reconcile-resets", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ResetWorker>(15, TimeUnit.MINUTES).build())
    }

    suspend fun save(candidates: List<ResetCandidate>, source: Source, observedAt: Instant): Int =
        mutex.withLock {
            store.ingest(candidates, source, observedAt).also { records ->
                records.forEach { record ->
                    if (!record.notified) schedule(record)
                    manager.cancel(record.key, NOTIFICATION_ID)
                }
            }.size
        }

    suspend fun delete(record: LimitRecord) = mutex.withLock {
        store.dao.delete(record.provider, record.window)
        work.cancelUniqueWork("reset-${record.key}")
        manager.cancel(record.key, NOTIFICATION_ID)
    }

    fun canNotify(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun checkDue() {
        work.enqueueUniqueWork("check-reset-permissions", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ResetWorker>().build())
    }

    private fun schedule(record: LimitRecord) {
        val delay = Duration.between(Instant.now(), record.resetAt).toMillis().coerceAtLeast(0)
        work.enqueueUniqueWork("reset-${record.key}", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ResetWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }

    suspend fun notifyDue() = mutex.withLock {
        val now = Instant.now()
        for (record in store.dao.pending()) {
            if (record.resetAt > now) continue
            if (Duration.between(record.resetAt, now) >= Duration.ofHours(24)) {
                store.dao.save(record.copy(notified = true))
                continue
            }
            if (!canNotify()) continue
            val content = when (record.window) {
                LimitWindow.SHORT -> "Das kurzfristige Nutzungslimit sollte jetzt zurückgesetzt sein."
                LimitWindow.WEEKLY -> "Das wöchentliche Nutzungslimit sollte jetzt zurückgesetzt sein."
                LimitWindow.UNKNOWN -> "Das erfasste Nutzungslimit sollte jetzt zurückgesetzt sein."
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle("${record.provider.title} · Limit-Reset")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            // The user can revoke permission between the check and the framework call.
            try {
                manager.notify(record.key, NOTIFICATION_ID, notification)
                store.dao.save(record.copy(notified = true))
            } catch (_: SecurityException) {
                continue
            }
        }
    }

    companion object {
        const val CHANNEL = "limit_resets"
        private const val NOTIFICATION_ID = 1
    }
}

class ResetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as LimitApplication).notifyDue()
        return Result.success()
    }
}
