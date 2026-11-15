package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.service.quicksettings.Tile
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.example.data.import.LinkImporter
import com.example.data.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Platform entry points are declarations, not code paths a unit test can call, so this checks
 * the merged manifest plus the state mappings that the tile and widget render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickControlsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resolves(intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == context.packageName }

    @Test
    fun everySupportedSchemeIsRegisteredAsADeepLink() {
        val registered = LinkImporter.supportedSchemes - setOf("content", "file")
        assertEquals(35, registered.size)
        registered.forEach { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com/payload"))
            assertTrue("$scheme:// is not registered", resolves(intent))
        }
    }

    @Test
    fun contentAndFileAreNotRegisteredGlobally() {
        // A global content:// filter would let any app hand us arbitrary URIs.
        assertTrue(!resolves(Intent(Intent.ACTION_VIEW, Uri.parse("content://media/1"))))
        assertTrue(!resolves(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.txt"))))
    }

    @Test
    fun textSharesAreAccepted() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "vless://id@host:443#node")
        }
        assertTrue("SEND text/plain is not registered", resolves(send))
    }

    @Test
    fun bootReceiverAndWidgetAreDeclared() {
        val boot = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        assertTrue("BootReceiver must be exported to receive BOOT_COMPLETED", boot.exported)
        assertEquals(
            listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED),
            bootActions().sorted()
        )

        // Robolectric does not surface <meta-data> from getPackageInfo, so the appwidget
        // registration is asserted against the merged manifest that actually ships.
        val widget = mergedManifestElements("receiver")
            .firstOrNull { it.android("name")?.endsWith("LuxyWidgetProvider") == true }
        assertNotNull("LuxyWidgetProvider receiver is missing from the manifest", widget)
        val providerMeta = widget!!.childElements("meta-data")
            .firstOrNull { it.android("name") == "android.appwidget.provider" }
        assertNotNull("widget provider meta-data is missing", providerMeta)
        assertEquals("@xml/widget_luxy_info", providerMeta!!.android("resource"))
        assertTrue(
            "widget info resource does not exist",
            context.resources.getIdentifier("widget_luxy_info", "xml", context.packageName) != 0
        )
    }

    @Test
    fun widgetLayoutIsInflatableByRemoteViews() {
        val views = RemoteViews(context.packageName, R.layout.widget_luxy)
        val inflated = views.apply(context, FrameLayout(context))
        assertNotNull(inflated.findViewById(R.id.widget_status))
        assertNotNull(inflated.findViewById(R.id.widget_dot))
        assertNotNull(inflated.findViewById(R.id.widget_root))
    }

    @Test
    fun tileIsToggleableAndRegistered() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, QuickToggleTileService::class.java),
            PackageManager.GET_META_DATA
        )
        assertTrue("the tile service must be exported", service.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)

        val declared = mergedManifestElements("service")
            .firstOrNull { it.android("name")?.endsWith("QuickToggleTileService") == true }
        assertNotNull("QuickToggleTileService is missing from the manifest", declared)
        val toggleable = declared!!.childElements("meta-data")
            .firstOrNull {
                it.android("name") == "android.service.quicksettings.TOGGLEABLE_TILE"
            }
        assertEquals("true", toggleable?.android("value"))
        assertTrue(
            "QS_TILE action is missing",
            declared.childElements("intent-filter").any { filter ->
                filter.childElements("action")
                    .any { it.android("name") == "android.service.quicksettings.action.QS_TILE" }
            }
        )
    }

    private fun bootActions(): List<String> =
        mergedManifestElements("receiver")
            .firstOrNull { it.android("name")?.endsWith("BootReceiver") == true }
            ?.childElements("intent-filter")
            ?.flatMap { it.childElements("action") }
            ?.mapNotNull { it.android("name") }
            .orEmpty()

    private fun mergedManifestElements(tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifestFile())
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun mergedManifestFile(): File {
        val candidates = listOf(
            File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
            File("app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("merged manifest not found in ${candidates.map { it.absolutePath }}")
    }

    private fun Element.childElements(tag: String): List<Element> {
        val children = childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }
    }

    private fun Element.android(name: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf { it.isNotEmpty() }

    @Test
    fun xudpKeySelfHealsFromTheLegacyUuid() {
        // 3.0.0/3.0.2 stored a 36-char UUID; the core rejects anything that is not 32 bytes.
        val prefs = context.getSharedPreferences("luxy_core_env", Context.MODE_PRIVATE)
        prefs.edit().putString("xudp_base_key", "ab41d7dd-37c0-4466-a129-94588c148801").commit()

        val healed = com.example.xraycore.XrayEngine.xudpKey(context)
        assertEquals(32, healed.toByteArray(Charsets.UTF_8).size)
        assertEquals("the healed key must persist", healed, com.example.xraycore.XrayEngine.xudpKey(context))
    }

    @Test
    fun tileReflectsTheTunnelState() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(ConnectionState.CONNECTED))
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(ConnectionState.DISCONNECTED))
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(ConnectionState.CONNECTING))
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(ConnectionState.ERROR))

        assertEquals(R.string.tile_subtitle_connected, tileSubtitleRes(ConnectionState.CONNECTED))
        assertEquals(R.string.tile_subtitle_connecting, tileSubtitleRes(ConnectionState.CONNECTING))
        assertEquals(R.string.tile_subtitle_switching, tileSubtitleRes(ConnectionState.FAILOVER))
        assertEquals(R.string.tile_subtitle_error, tileSubtitleRes(ConnectionState.ERROR))
        assertEquals(R.string.tile_subtitle_disconnected, tileSubtitleRes(ConnectionState.DISCONNECTED))
    }

    @Test
    fun widgetShowsOneLabelAndColourPerState() {
        assertEquals(R.string.orb_connected, widgetLabelRes(ConnectionState.CONNECTED))
        assertEquals(R.string.orb_connecting, widgetLabelRes(ConnectionState.CONNECTING))
        assertEquals(R.string.orb_reconnecting, widgetLabelRes(ConnectionState.RECONNECTING))
        assertEquals(R.string.orb_error, widgetLabelRes(ConnectionState.ERROR))
        assertEquals(R.string.widget_status_idle, widgetLabelRes(ConnectionState.DISCONNECTED))

        // Connected green, idle muted, error red — all three must be distinguishable.
        assertNotEquals(
            widgetStatusColor(ConnectionState.DISCONNECTED),
            widgetStatusColor(ConnectionState.CONNECTED)
        )
        assertNotEquals(
            widgetStatusColor(ConnectionState.CONNECTED),
            widgetStatusColor(ConnectionState.ERROR)
        )
    }
}
