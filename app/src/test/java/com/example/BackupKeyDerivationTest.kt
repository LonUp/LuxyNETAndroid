package com.example

import com.example.core.BackupKeyDerivation
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupKeyDerivationTest {
    @Test
    fun fallbackMatchesPbkdf2Sha256KnownAnswer() {
        val key = BackupKeyDerivation.pbkdf2Sha256("password", "salt".toByteArray(), 2)
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            key.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun fallbackMatchesV1BackupKeysForPersianPassphrases() {
        val passphrase = "گذرواژهٔ پشتیبان🔐"
        val salt = ByteArray(16) { it.toByte() }
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)).encoded
        assertArrayEquals(expected, BackupKeyDerivation.pbkdf2Sha256(passphrase, salt, 100_000))
    }
}
