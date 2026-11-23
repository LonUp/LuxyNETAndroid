package com.example.core

import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object BackupKeyDerivation {
    fun aes256(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: NoSuchAlgorithmException) {
            // Android 24–25 provide HmacSHA256 but not this SecretKeyFactory algorithm.
            null
        }
        val key = if (factory != null) {
            val password = passphrase.toCharArray()
            val spec = try {
                PBEKeySpec(password, salt, iterations, 256)
            } finally {
                password.fill('\u0000')
            }
            try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } else {
            pbkdf2Sha256(passphrase, salt, iterations)
        }
        return try {
            SecretKeySpec(key, "AES")
        } finally {
            key.fill(0)
        }
    }

    /** RFC 8018 PBKDF2, specialized to the one SHA-256 block needed for AES-256. */
    internal fun pbkdf2Sha256(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val mac = Mac.getInstance("HmacSHA256")
        val password = passphrase.toByteArray(Charsets.UTF_8)
        try {
            mac.init(SecretKeySpec(password, "HmacSHA256"))
        } finally {
            password.fill(0)
        }
        mac.update(salt)
        val block = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = block.copyOf()
        try {
            repeat(iterations - 1) {
                mac.update(block)
                mac.doFinal(block, 0)
                for (i in result.indices) result[i] = (result[i].toInt() xor block[i].toInt()).toByte()
            }
        } finally {
            block.fill(0)
        }
        return result
    }
}
