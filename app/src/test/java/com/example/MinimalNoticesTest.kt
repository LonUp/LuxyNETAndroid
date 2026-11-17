package com.example

import com.example.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalNoticesTest {
    @Test fun partialImportLocalizesCountsAndNestedHttpStatus() {
        val input = "Imported 12 server(s). Rejected 3: Subscription returned HTTP 503. Ignored unsafe or app-managed configuration directives"
        val translated = UiText.translate("fa", input)
        assertFalse(translated.any { it in '0'..'9' })
        assertTrue(translated.contains("۱۲"))
        assertTrue(translated.contains("۳"))
        assertTrue(translated.contains("۵۰۳"))
        assertTrue(translated.contains("HTTP"))
        assertEquals(input, UiText.translate("en", input))
    }

    @Test fun multiSentenceFailureIsNotSplitIntoUntranslatedFragments() {
        val translated = UiText.translate("fa", "Nothing imported. Rejected 1: Subscription request failed. Check your network and URL")
        assertFalse(translated.contains("Subscription"))
        assertFalse(translated.contains("Check"))
        assertFalse(translated.any { it in '0'..'9' })
    }
}
