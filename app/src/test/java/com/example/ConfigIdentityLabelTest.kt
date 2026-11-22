package com.example

import com.example.core.ConfigFingerprint
import com.example.core.ConfigIdentityLabel
import com.example.data.local.ServerProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The identity caption must distinguish same-named configs without exposing connection secrets. */
class ConfigIdentityLabelTest {

    private fun profile(
        port: Int = 443,
        secret: String = "2f9c1b7e-0a44-4c8f-9d36-1c2b3a4d5e6f",
        address: String = "us1.node.example.net",
        protocol: String = "VLESS"
    ) = ServerProfileEntity(
        name = "United States",
        protocol = protocol,
        address = address,
        port = port,
        uuidOrPassword = secret,
        sni = "sni.node.example.net",
        hostHeader = "cdn.node.example.net",
        path = "/private/path",
        publicKey = "PUBLICKEYMATERIAL",
        shortId = "abcd1234"
    )

    @Test
    fun sameNameDifferentPortGetsDifferentLabels() {
        val first = profile(port = 443)
        val second = profile(port = 8443)

        assertEquals("United States", first.name)
        assertEquals("United States", second.name)
        assertNotEquals(
            ConfigIdentityLabel.shortLabel(first),
            ConfigIdentityLabel.shortLabel(second)
        )
    }

    @Test
    fun sameNameDifferentSecretGetsDifferentLabels() {
        val first = profile(secret = "2f9c1b7e-0a44-4c8f-9d36-1c2b3a4d5e6f")
        val second = profile(secret = "8d1e5a90-77bb-42c1-8f0d-6e5d4c3b2a10")

        assertEquals("United States", first.name)
        assertEquals("United States", second.name)
        assertNotEquals(
            ConfigIdentityLabel.shortLabel(first),
            ConfigIdentityLabel.shortLabel(second)
        )
    }

    @Test
    fun labelIsProtocolPortAndShortFingerprintOnly() {
        val subject = profile(port = 8443)
        val label = ConfigIdentityLabel.shortLabel(subject)
        val fingerprint = ConfigFingerprint.compute(subject)

        assertEquals("VLESS · 8443 · ${fingerprint.take(4)}", label)
        assertTrue(label, Regex("^VLESS · 8443 · [0-9a-f]{4}$").matches(label))
        assertFalse("address leaked", label.contains(subject.address, ignoreCase = true))
        assertFalse("sni leaked", label.contains(subject.sni, ignoreCase = true))
        assertFalse("host leaked", label.contains(subject.hostHeader, ignoreCase = true))
        assertFalse("path leaked", label.contains(subject.path, ignoreCase = true))
        assertFalse("secret leaked", label.contains(subject.uuidOrPassword, ignoreCase = true))
        assertFalse("public key leaked", label.contains(subject.publicKey, ignoreCase = true))
        assertFalse("full fingerprint leaked", label.contains(fingerprint, ignoreCase = true))
    }

    @Test
    fun blankProtocolIsOmitted() {
        val label = ConfigIdentityLabel.shortLabel(profile(port = 443, protocol = ""))

        assertTrue(label, label.startsWith("443 · "))
    }
}
