package com.example.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSizeFormatTest {
    @Test fun binaryQuantitiesAreConvertedNotRelabeled() {
        assertEquals("1.02 KB", DataSizeFormat.format(1_024L, "en"))
        assertEquals("1.05 MB", DataSizeFormat.format(1_024L * 1_024L, "en"))
        assertEquals("2.15 GB", DataSizeFormat.format(2L * 1_024L * 1_024L * 1_024L, "en"))
        assertEquals("10.7 GB", DataSizeFormat.format(10L * 1_024L * 1_024L * 1_024L, "en"))
        assertEquals("8.39 MB", DataSizeFormat.format(8L * 1_024L * 1_024L, "en"))
    }

    @Test fun decimalBoundariesKeepTheirUnit() {
        assertEquals("0 B", DataSizeFormat.format(0L, "en"))
        assertEquals("999 B", DataSizeFormat.format(999L, "en"))
        assertEquals("1.00 KB", DataSizeFormat.format(1_000L, "en"))
        assertEquals("1.00 MB", DataSizeFormat.format(1_000_000L, "en"))
        assertEquals("1.50 GB", DataSizeFormat.format(1_500_000_000L, "en"))
        assertEquals("1.00 TB", DataSizeFormat.format(1_000_000_000_000L, "en"))
    }

    @Test fun persianUsesFamiliarNamesAndDigits() {
        assertEquals("۲٫۱۵ گیگابایت", DataSizeFormat.format(2L * 1_024L * 1_024L * 1_024L, "fa"))
        assertEquals("۱٫۰۵ مگابایت", DataSizeFormat.format(1_024L * 1_024L, "fa"))
        assertEquals("۱٫۰۲ کیلوبایت", DataSizeFormat.format(1_024L, "fa"))
        assertEquals("۹۹۹ بایت", DataSizeFormat.format(999L, "fa"))
    }

    @Test fun negativeCountersDoNotDisplayAsNegativeTraffic() {
        assertEquals("0 B", DataSizeFormat.format(-1L, "en"))
    }
}
