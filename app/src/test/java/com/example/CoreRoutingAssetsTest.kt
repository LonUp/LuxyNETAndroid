package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.xraycore.CoreRoutingAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoreRoutingAssetsTest {
    @Test
    fun packagedDatabasesSurviveFirstStartReuseAndInterruptedExtraction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = Properties().apply {
            context.assets.open("routing-assets.properties").use { load(it) }
        }
        val directory = CoreRoutingAssets.directory(context)
        fun verify() {
            for (name in listOf("geoip", "geosite")) {
                val file = File(directory, "$name.dat")
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(manifest.getProperty("$name.sha256"), hash)
            }
        }
        verify()
        val geoip = File(directory, "geoip.dat")
        assertTrue(geoip.setLastModified(1234000L))
        assertEquals(directory, CoreRoutingAssets.directory(context))
        assertEquals("Cached extraction must not rewrite the database", 1234000L, geoip.lastModified())
        geoip.writeBytes(byteArrayOf(0))
        File(directory, "geoip.tmp").writeText("interrupted previous extraction")
        CoreRoutingAssets.directory(context)
        verify()
        assertTrue("No partial asset remains", !File(directory, "geoip.tmp").exists())
    }
}
