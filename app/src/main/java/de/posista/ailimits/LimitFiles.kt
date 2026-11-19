package de.posista.ailimits

import java.io.InputStream
import java.io.OutputStream

internal fun readResetFile(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
    val data = StringBuilder()
    val buffer = CharArray(4096)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(data.length + count <= ResetParser.MAX_FILE_INPUT)
        data.append(buffer, 0, count)
    }
    data.toString().removePrefix("\uFEFF")
}

internal fun writeResetFile(output: OutputStream, resets: List<ResetCandidate>) {
    val text = resets.joinToString("\n", postfix = "\n", transform = ResetParser::formatLine)
    require(text.length <= ResetParser.MAX_FILE_INPUT)
    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}
