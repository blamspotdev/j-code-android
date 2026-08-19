package dev.jcode.feature.marketplace

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

/**
 * Opens **format-2** `.jext` packages: signed (Ed25519) + encrypted (AES-256-GCM), produced by the
 * `jcode-jext-sign-enc-tool` (`jsign`). Container layout:
 *
 *   magic "JEXT" (4) | format=2 (1) | headerLen u32 BE (4) | header JSON | payload
 *   payload = IV(12) || AES-256-GCM ciphertext || tag(16)   (the encrypted inner plain-.jext ZIP)
 *
 * The Ed25519 signature (header.sig.value) covers the whole payload, so it proves the package is
 * official and untampered. Decryption yields the ordinary inner `.jext` ZIP, which the installer then
 * verifies (per-file SHA-256) as usual.
 *
 * SECURITY: the [ED25519_PUB_B64U] verify key is the real authenticity guarantee. The [AES256_KEY_B64]
 * is embedded for offline decryption, so it is extractable from the (decompilable) app — the encryption
 * layer is obfuscation ("not casually unzippable"), NOT true secrecy.
 */
internal object JextCrypto {
    // From `jsign keygen` (keyId jcode-official-v1). Rotating the key requires re-signing every package.
    private const val ED25519_PUB_B64U = "qdl0pG3HU48ktRh6WgV4TIaLApshCM-6wC8uuZdnpjs"
    private const val AES256_KEY_B64 = "n0kUZ/LOuraD7FcYplF+GxQHnKKhRBUBDh54L4QGDiA="

    private const val FORMAT_SIGNED = 2

    /** True when [bytes] is a signed/encrypted format-2 container (vs a plain `.jext` ZIP). */
    fun isSignedJext(bytes: ByteArray): Boolean =
        bytes.size > 9 &&
            bytes[0] == 'J'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'X'.code.toByte() && bytes[3] == 'T'.code.toByte() &&
            (bytes[4].toInt() and 0xFF) == FORMAT_SIGNED

    /** Verify the Ed25519 signature and AES-256-GCM-decrypt to the inner plain `.jext` ZIP. Throws on any failure. */
    fun openSignedJext(bytes: ByteArray): ByteArray {
        require(isSignedJext(bytes)) { "not a signed .jext" }
        val headerLen = ((bytes[5].toInt() and 0xFF) shl 24) or ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        require(headerLen in 1..(bytes.size - 9)) { "corrupt signed .jext (bad header length)" }
        val header = JSONObject(String(bytes, 9, headerLen, Charsets.UTF_8))
        val payload = bytes.copyOfRange(9 + headerLen, bytes.size)
        require(payload.size > 28) { "corrupt signed .jext (payload too small)" }

        // 1) Authenticity: Ed25519 over the whole payload (IV||ciphertext||tag).
        val sig = header.optJSONObject("sig") ?: error("signed .jext has no signature")
        val sigBytes = Base64.getDecoder().decode(sig.optString("value"))
        val pub = Ed25519PublicKeyParameters(Base64.getUrlDecoder().decode(ED25519_PUB_B64U), 0)
        val verifier = Ed25519Signer().apply { init(false, pub); update(payload, 0, payload.size) }
        if (!verifier.verifySignature(sigBytes)) {
            error("signed .jext failed signature check — not an official package or it was tampered with")
        }

        // 2) Decrypt: AES-256-GCM with IV = first 12 bytes, tag = last 16 (Java expects ciphertext||tag).
        val iv = payload.copyOfRange(0, 12)
        val ctPlusTag = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(AES256_KEY_B64), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ctPlusTag)
    }
}
