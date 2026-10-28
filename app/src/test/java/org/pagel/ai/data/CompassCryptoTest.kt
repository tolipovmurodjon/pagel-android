package org.pagel.ai.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.compass.CompassCrypto

/** The Compass's seal: AES-256-GCM per item, bound to its id; the recovery key through scrypt. */
class CompassCryptoTest {
    private val key = CompassCrypto.newDataKey()
    private val words = "Plain goal zebra: build the school".toByteArray()

    @Test fun aSealOpensOnlyWithItsKeyAndItsId() {
        val blob = CompassCrypto.seal(key, "g_abc123", words)
        assertArrayEquals(words, CompassCrypto.open(key, "g_abc123", blob))
        assertNull(CompassCrypto.open(key, "g_other1", blob))
        assertNull(CompassCrypto.open(CompassCrypto.newDataKey(), "g_abc123", blob))
        assertFalse(String(CompassCrypto.unb64(blob)).contains("zebra"))
        val raw = CompassCrypto.unb64(blob)
        assertEquals(1, raw[0].toInt())
        assertEquals(1 + 12 + words.size + 16, raw.size)
    }

    @Test fun everySealHasItsOwnNonceAndAChangedByteFails() {
        val a = CompassCrypto.seal(key, "g_abc123", words)
        val b = CompassCrypto.seal(key, "g_abc123", words)
        assertNotEquals(a, b)
        val raw = CompassCrypto.unb64(a)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(CompassCrypto.open(key, "g_abc123", CompassCrypto.b64(raw)))
        assertNull(CompassCrypto.open(key, "g_abc123", "not a blob"))
    }

    @Test fun theRecoveryPassphraseOpensTheDataKeyAndAWrongOneDoesNot() {
        val blob = CompassCrypto.sealWithPassphrase(key, "correct horse battery".toCharArray(), memoryLog2Kb = 10, passes = 1)
        assertArrayEquals(key, CompassCrypto.openWithPassphrase(blob, "correct horse battery".toCharArray()))
        assertNull(CompassCrypto.openWithPassphrase(blob, "wrong horse battery".toCharArray()))
        val raw = CompassCrypto.unb64(blob)
        assertTrue(raw[0].toInt() == 1)
        assertEquals("Argon2id", 2, raw[1].toInt())
    }

    @Test fun theDefaultRecoveryCostIsArgon2idWithSixtyFourMegabytes() {
        val raw = CompassCrypto.unb64(CompassCrypto.sealWithPassphrase(key, "correct horse battery".toCharArray()))
        assertEquals(2, raw[1].toInt())
        assertEquals(16, raw[2].toInt())
        assertEquals(3, raw[3].toInt())
    }

    @Test fun aBlobIsBoundToItsKindParentAndDates() {
        val bound = CompassCrypto.aad("g_abc123", "season", "g_year01", "2026-11-01", "2027-01-30")
        val blob = CompassCrypto.seal(key, bound, words)
        assertArrayEquals(words, CompassCrypto.open(key, bound, blob))
        assertNull(CompassCrypto.open(key, CompassCrypto.aad("g_abc123", "someday", "g_year01", "2026-11-01", "2027-01-30"), blob))
        assertNull(CompassCrypto.open(key, CompassCrypto.aad("g_abc123", "season", "g_other1", "2026-11-01", "2027-01-30"), blob))
        assertNull(CompassCrypto.open(key, CompassCrypto.aad("g_abc123", "season", "g_year01", "2027-01-31", "2027-05-01"), blob))
    }
}
