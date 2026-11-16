package com.example.i18n

/** Display-only digits. Machine-readable values stay ASCII. */
object Digits {
    private val persian = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun localize(text: String, language: String?): String {
        if (language != "fa" || text.isEmpty()) return text
        return buildString(text.length) {
            text.forEach { ch ->
                append(if (ch in '0'..'9') persian[ch - '0'] else ch)
            }
        }
    }
}
