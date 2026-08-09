package org.pagel.ai.net.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [Ed25519] to the RFC 8032 §7.1 reference vectors.
 *
 * Our own `sign`/`verify` agree with each other by construction — both go through BouncyCastle — so
 * a whole-implementation deviation (Ed25519ph, Ed25519ctx, a swapped key half) would be invisible to
 * every other test in this repo while still being rejected by the gateway's `crypto.verify`, which
 * implements pure Ed25519. Only an external vector can catch that, which is why this exists: the
 * live gateway reports `device signature invalid` for a payload whose every field is verifiably
 * correct.
 */
class Ed25519Rfc8032Test {

    /** RFC 8032 §7.1, TEST 1 — empty message. */
    @Test
    fun `test vector 1 - empty message`() = check(
        secret = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        public = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        message = "",
        signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a3" +
            "3bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    /** RFC 8032 §7.1, TEST 2 — single byte 0x72. */
    @Test
    fun `test vector 2 - one byte`() = check(
        secret = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
        public = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        message = "72",
        signature = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15" +
            "996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    /** RFC 8032 §7.1, TEST 3 — two bytes. */
    @Test
    fun `test vector 3 - two bytes`() = check(
        secret = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
        public = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
        message = "af82",
        signature = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16" +
            "f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
    )

    private fun check(secret: String, public: String, message: String, signature: String) {
        val secretRaw = secret.hexToBytes()
        val publicRaw = public.hexToBytes()
        val messageRaw = message.hexToBytes()

        // The public key must be derivable from the secret — catches a swapped/misparsed key half.
        assertArrayEquals(
            "derived public key must match the RFC vector",
            publicRaw,
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(secretRaw, 0)
                .generatePublicKey().encoded,
        )

        assertEquals(
            "signature must byte-match the RFC vector — a mismatch means this is not pure Ed25519",
            signature,
            Ed25519.sign(secretRaw, messageRaw).toHex(),
        )
        assert(Ed25519.verify(publicRaw, messageRaw, signature.hexToBytes()))
    }

    private fun String.hexToBytes(): ByteArray =
        if (isEmpty()) ByteArray(0)
        else chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
