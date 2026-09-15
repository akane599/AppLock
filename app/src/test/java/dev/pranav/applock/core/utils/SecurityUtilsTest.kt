package dev.pranav.applock.core.utils

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecurityUtilsTest {
    @Test fun verifiesPasswordsAndPatternsWithoutStoringPlaintext() {
        for (secret in listOf("1234", "some:password", "0,1,4,7")) {
            val hash = SecurityUtils.hashPassword(secret)
            assertNotEquals(secret, hash)
            assertTrue(SecurityUtils.isSaltedHash(hash))
            assertTrue(SecurityUtils.verifyPassword(secret, hash))
            assertFalse(SecurityUtils.verifyPassword("incorrect", hash))
        }
    }

    @Test fun legacyColonPasswordIsNotMisidentifiedAsHash() {
        assertFalse(SecurityUtils.isSaltedHash("YWJj:ZGVm"))
        assertFalse(SecurityUtils.isSaltedHash(":"))
    }

    @Test fun existingAndroidBase64HashesRemainCompatible() {
        // Android Base64.NO_WRAP and Java's basic encoder use the same padded encoding.
        val salt = ByteArray(16) { it.toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").apply { update(salt) }
            .digest("1234".toByteArray(Charsets.UTF_8))
        val stored = "AAECAwQFBgcICQoLDA0ODw==:" + Base64.getEncoder().encodeToString(digest)
        assertEquals(stored, SecurityUtils.hashPassword("1234", salt))
        assertTrue(SecurityUtils.verifyPassword("1234", stored))
    }

    @Test fun malformedHashIsRejected() {
        for (value in listOf("", ":", "a:b:c", "not base64:???")) {
            assertFalse(SecurityUtils.verifyPassword("1234", value))
        }
    }
}
