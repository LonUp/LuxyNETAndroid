package com.example.core

import java.io.ByteArrayOutputStream

object Base64Compat {
    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encodeToString(bytes: ByteArray, urlSafe: Boolean = false, noPadding: Boolean = false): String {
        val alphabet = if (urlSafe) URL_ALPHABET else STD_ALPHABET
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0

            val chunk = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(chunk ushr 18) and 0x3F])
            sb.append(alphabet[(chunk ushr 12) and 0x3F])
            if (hasB1) sb.append(alphabet[(chunk ushr 6) and 0x3F]) else if (!noPadding) sb.append('=')
            if (hasB2) sb.append(alphabet[chunk and 0x3F]) else if (!noPadding) sb.append('=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val cleaned = input.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace('-', '+')
            .replace('_', '/')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsCollected = 0
        for (ch in cleaned) {
            if (ch == '=') break
            val valIdx = STD_ALPHABET.indexOf(ch)
            if (valIdx < 0) continue
            buffer = (buffer shl 6) or valIdx
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.write((buffer ushr bitsCollected) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
