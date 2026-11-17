package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.Base64Compat
import com.example.data.import.ProfileImporter
import com.example.data.local.LuxyVpnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.model.StabilitySettings
import com.example.data.repository.LuxyVpnRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MinimalImportTest {
    private lateinit var database: LuxyVpnDatabase
    private lateinit var repository: LuxyVpnRepository
    private lateinit var importer: ProfileImporter
    private lateinit var server: MockWebServer
    private val first = "vless://11111111-2222-3333-4444-555555555555@one.example:443?security=tls"
    private val second = "trojan://secret@two.example:443?sni=tls.example"

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, LuxyVpnDatabase::class.java).build()
        repository = LuxyVpnRepository(database, SettingsDataStore(context))
        runBlocking { repository.updateSettings { StabilitySettings() } }
        importer = ProfileImporter(repository)
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test fun subscriptionImportReturnsOnlyAfterNodesAndSubscriptionAreReadable() = runBlocking {
        server.enqueue(MockResponse().setBody(Base64Compat.encodeToString("$first\n$second".toByteArray()))
            .setBodyDelay(100, TimeUnit.MILLISECONDS)
            .setHeader("profile-title", "My subscription")
            .setHeader("subscription-userinfo", "upload=12; download=34; total=100"))
        val summary = importer.importText(server.url("/subscription?token=private").toString())
        assertEquals(2, summary.profiles)
        assertEquals(1, summary.subscriptions)
        assertTrue(summary.failures.isEmpty())
        val nodes = repository.getAllProfiles()
        val subscription = repository.getAllSubscriptions().single()
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.subscriptionId == subscription.id })
        assertEquals("My subscription", subscription.name)
        assertEquals(34L, subscription.downloadBytes)
        assertTrue(nodes.any { it.id == repository.getSettings().activeProfileId })
        assertFalse(repository.getSettings().experimentalModeEnabled)
    }

    @Test fun encryptedVlessSubscriptionPersistsWithoutExperimentalSettings() = runBlocking {
        val key = Base64Compat.encodeToString(ByteArray(32) { it.toByte() }, urlSafe = true, noPadding = true)
        val encryption = "mlkem768x25519plus.xorpub.0rtt.$key"
        val links = (1..10).joinToString("\n") { index ->
            "vless://11111111-2222-3333-4444-555555555555@node$index.example:443" +
                "?encryption=$encryption&security=none&type=tcp&headerType=none&flow=xtls-rprx-vision"
        }
        server.enqueue(MockResponse().setBody(Base64Compat.encodeToString(links.toByteArray())))
        val summary = importer.importText(server.url("/encrypted").toString())
        assertEquals(10, summary.profiles)
        assertEquals(1, summary.subscriptions)
        assertTrue(summary.failures.isEmpty())
        val nodes = repository.getAllProfiles()
        assertEquals(10, nodes.size)
        assertTrue(nodes.all { it.encryption == encryption && it.security == "NONE" })
        assertFalse(repository.getSettings().experimentalModeEnabled)
    }

    @Test fun failedRefreshNeverCountsSuccessOrOverwritesWorkingNodes() = runBlocking {
        val url = server.url("/subscription").toString()
        server.enqueue(MockResponse().setBody(first))
        assertEquals(1, importer.importText(url).profiles)
        val original = repository.getAllProfiles()
        val subscription = repository.getAllSubscriptions().single()
        for (response in listOf(
            MockResponse().setResponseCode(503),
            MockResponse().setBody("hy2://secret@unsupported.example:443"),
            MockResponse().setBody("<html>sign in</html>"),
            MockResponse().setBody("vless://id@broken.example:0?security=tls")
        )) {
            server.enqueue(response)
            val failed = importer.importText(url)
            assertEquals(0, failed.profiles)
            assertEquals(0, failed.subscriptions)
            assertTrue(failed.failures.isNotEmpty())
            assertEquals(original, repository.getAllProfiles())
            assertEquals(subscription, repository.getAllSubscriptions().single())
        }
    }

    @Test fun successfulRefreshReusesSubscriptionAndCommitsNewNodes() = runBlocking {
        val url = server.url("/subscription").toString()
        server.enqueue(MockResponse().setBody(first))
        importer.importText(url)
        val id = repository.getAllSubscriptions().single().id
        server.enqueue(MockResponse().setBody(second))
        val refreshed = importer.importText(url)
        assertEquals(1, refreshed.profiles)
        assertEquals(1, repository.getAllSubscriptions().size)
        assertEquals(id, repository.getAllSubscriptions().single().id)
        assertEquals("two.example", repository.getAllProfiles().single().address)
        assertEquals(repository.getAllProfiles().single().id, repository.getSettings().activeProfileId)
    }

    @Test fun cancelledNetworkImportCannotCreateSubscriptionOrNodes() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { importer.importText(server.url("/waiting").toString()) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        pending.cancelAndJoin()
        assertTrue(repository.getAllProfiles().isEmpty())
        assertTrue(repository.getAllSubscriptions().isEmpty())
    }

    @Test fun mixedImportStoresRealNodesAndReportsUnsupportedEntries() = runBlocking {
        val summary = importer.importText("$first\nhy2://secret@unsupported.example:443\n$second")
        assertEquals(2, summary.profiles)
        assertEquals(1, summary.failures.size)
        assertEquals(setOf("one.example", "two.example"), repository.getAllProfiles().map { it.address }.toSet())
        assertFalse(repository.getSettings().experimentalModeEnabled)
    }

    @Test fun oversizedSubscriptionResponseCannotOverwriteExistingNodes() = runBlocking {
        val url = server.url("/subscription").toString()
        server.enqueue(MockResponse().setBody(first))
        importer.importText(url)
        val original = repository.getAllProfiles()
        server.enqueue(MockResponse().setBody(second)
            .setHeader("Content-Length", com.example.core.ImportInput.MAX_BYTES + 1))
        val rejected = importer.importText(url)
        assertEquals(0, rejected.profiles)
        assertEquals(0, rejected.subscriptions)
        assertTrue(rejected.failures.contains(com.example.core.ImportInput.TOO_LARGE))
        assertEquals(original, repository.getAllProfiles())
    }

    @Test fun subscriptionRefreshPreservesStoredUnsupportedProfilesWithoutImportingNewOnes() = runBlocking {
        val url = server.url("/subscription").toString()
        server.enqueue(MockResponse().setBody(first))
        importer.importText(url)
        val old = repository.getAllProfiles().single()
        val preserved = old.copy(id = "old-experimental", protocol = "HYSTERIA2")
        database.serverProfileDao().upsert(preserved)
        server.enqueue(MockResponse().setBody(second))
        val refreshed = importer.importText(url)
        assertEquals(1, refreshed.profiles)
        assertEquals(2, repository.getAllProfiles().size)
        assertEquals(preserved, repository.getAllProfiles().first { it.id == preserved.id })
    }

    @Test fun invalidReplacementIsRejectedBeforeDeletingWorkingNodes() = runBlocking {
        server.enqueue(MockResponse().setBody(first))
        importer.importText(server.url("/subscription").toString())
        val subscription = repository.getAllSubscriptions().single()
        val original = repository.getAllProfiles()
        try {
            repository.importSubscription(subscription, listOf(original.single().copy(port = 0)))
            throw AssertionError("Invalid replacement should be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(original, repository.getAllProfiles())
        }
    }
}
