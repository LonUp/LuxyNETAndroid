package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload blockers are cheaper to catch here than in a store console: a stale version code or a
 * non-semver name fails the build. `versionName` drives `versionCode` (major*10_000 + minor*100
 * + patch), so bumping one without the other is impossible.
 */
class ReleaseMetadataTest {

    @Test
    fun versionNameIsSemver() {
        assertTrue(
            "versionName must be a semver triple, was '${BuildConfig.VERSION_NAME}'",
            BuildConfig.VERSION_NAME.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))
        )
    }

    @Test
    fun versionCodeMatchesTheNameAndBeatsTheLegacyBuild() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME
            .split('.')
            .map { it.toIntOrNull() ?: -1 }
        assertTrue("versionName parts must parse, was '${BuildConfig.VERSION_NAME}'", minor >= 0 && patch >= 0)
        assertEquals(major * 10_000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
        // The pre-P3 build shipped versionCode = 1 with versionName "3.0"; a store rejects reuse.
        assertTrue("versionCode must exceed the legacy build", BuildConfig.VERSION_CODE > 1)
        assertTrue(
            "major*10_000 scheme requires a band of at least 100 per minor",
            minor in 0..99 && patch in 0..99
        )
    }

    @Test
    fun applicationIdIsFrozen() {
        assertEquals("com.aistudio.luxyvpn.kxmzpq", BuildConfig.APPLICATION_ID)
    }
}
