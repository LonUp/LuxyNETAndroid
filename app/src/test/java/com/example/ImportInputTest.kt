package com.example

import com.example.core.ImportInput
import com.example.core.ImportTooLargeException
import com.example.data.import.LinkImporter
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportInputTest {
    @Test fun utf8LimitCountsBytesNotCharacters() {
        assertTrue(ImportInput.isWithinLimit("a".repeat(ImportInput.MAX_BYTES)))
        assertFalse(ImportInput.isWithinLimit("a".repeat(ImportInput.MAX_BYTES + 1)))
        assertFalse(ImportInput.isWithinLimit("\u20ac".repeat(ImportInput.MAX_BYTES / 3 + 1)))
        assertTrue(ImportInput.isWithinLimit("\ud83d\ude00".repeat(ImportInput.MAX_BYTES / 4)))
    }

    @Test fun streamRejectsOversizedBodiesInsteadOfReturningPartialText() {
        val source = ByteArrayInputStream(ByteArray(ImportInput.MAX_BYTES + 1))
        val error = assertThrows(ImportTooLargeException::class.java) { ImportInput.readText(source) }
        assertEquals(ImportInput.TOO_LARGE, error.message)
    }

    @Test fun clipboardImportRejectsWholeOversizedInputBeforeParsingAnyNodes() {
        val text = "socks://host.example:1080\n" + "x".repeat(ImportInput.MAX_BYTES)
        val result = LinkImporter.handleMany(text).single() as LinkImporter.Result.Unsupported
        assertEquals(ImportInput.TOO_LARGE, result.reason)
    }

    @Test fun streamPreservesValidUtf8Text() {
        val text = "سرور \ud83c\udf0d\n{\"outbounds\":[]}"
        assertEquals(text, ImportInput.readText(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }
}
