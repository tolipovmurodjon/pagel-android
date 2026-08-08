package org.pagel.ai.net.identity

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * This device's Ed25519 identity for the gateway. [deviceId] is the lowercase-hex SHA-256 of the
 * raw 32-byte public key, so it's derivable from the key alone rather than a separately issued id.
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyRaw: ByteArray,
    val privateKeyRaw: ByteArray,
) {
    // Never print private key material, even via a debugger watch or a stray log.toString() call.
    override fun toString(): String =
        "DeviceIdentity(deviceId=$deviceId, publicKeyRaw=<${publicKeyRaw.size} bytes>, privateKeyRaw=<redacted>)"
}

fun DeviceIdentity.publicKeyB64Url(): String = base64UrlNoPad(publicKeyRaw)

/** Base64url per RFC 4648 §5, no `=` padding — the encoding the gateway expects for `publicKey`/`signature`. */
fun base64UrlNoPad(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * Ed25519 keygen/sign/verify via BouncyCastle's lightweight API. minSdk 30 predates platform
 * Ed25519 (API 33), and the lightweight API needs no `Security.addProvider(...)` JCE registration,
 * so it works unchanged in plain JVM unit tests.
 */
object Ed25519 {

    fun generate(): DeviceIdentity {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKeyRaw = privateKey.generatePublicKey().encoded
        return DeviceIdentity(
            deviceId = sha256Hex(publicKeyRaw),
            publicKeyRaw = publicKeyRaw,
            privateKeyRaw = privateKey.encoded,
        )
    }

    fun sign(privateKeyRaw: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
