package com.example.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A single UTF-8 ceiling for clipboard text, files, subscription bodies and decoded lists. */
object ImportInput {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val TOO_LARGE = "Import exceeds the 8 MiB size limit"

    fun isWithinLimit(text: String): Boolean {
        if (text.length <= MAX_BYTES / 3) return true
        if (text.length > MAX_BYTES) return false
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > MAX_BYTES) return false
        }
        return true
    }

    fun readText(input: InputStream): String {
        val bytes = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) throw ImportTooLargeException()
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}

class ImportTooLargeException : IOException(ImportInput.TOO_LARGE)
