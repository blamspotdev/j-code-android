package dev.jcode.core.distro.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * adb's AUTH handshake, device side.
 *
 * The device sends a random `AUTH TOKEN` and the client answers `AUTH SIGNATURE` with the token
 * signed by one of its private keys, or — once it runs out of keys — `AUTH RSAPUBLICKEY`, which on a
 * real phone raises the "Allow USB debugging?" dialog. This daemon has no such dialog and no notion
 * of a trusted user gesture, so only a *signature* by an enrolled key is ever accepted.
 *
 * The signature is genuine RSA: adb calls `RSA_sign(NID_sha1, token, 20, ...)`, which signs the token
 * **as if it were already a SHA-1 digest**. So verification cannot use `Signature("SHA1withRSA")` —
 * that would hash the token a second time. It has to be the raw public-key operation followed by an
 * EMSA-PKCS1-v1_5 check against `DigestInfo(SHA-1, token)`, which is what [verify] does.
 */
object AdbAuth {

    /** adb's `ADB_TOKEN_SIZE`. */
    const val TOKEN_BYTES: Int = 20

    private const val MODULUS_BYTES = 256
    private const val MODULUS_WORDS = MODULUS_BYTES / 4

    /** DER `AlgorithmIdentifier` + digest header for SHA-1, the prefix of a PKCS#1 v1.5 DigestInfo. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
    )

    private val random = SecureRandom()

    fun newToken(): ByteArray = ByteArray(TOKEN_BYTES).also(random::nextBytes)

    /**
     * Parses a line of an `adbkey.pub` — base64 of adb's own `RSAPublicKey` struct, optionally
     * followed by a ` user@host` comment.
     *
     * The struct is `{ uint32 modulus_size_words, uint32 n0inv, uint8 modulus[256],
     * uint8 rr[256], uint32 exponent }`, all little-endian. `n0inv` and `rr` are Montgomery
     * precomputations for the bootloader's verifier and carry no information a `BigInteger` needs.
     */
    fun parsePublicKey(line: String): PublicKey? {
        val encoded = line.trim().substringBefore(' ').takeIf { it.isNotEmpty() } ?: return null
        val blob = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (blob.size < 8 + 2 * MODULUS_BYTES + 4) return null
        if (blob.int(0) != MODULUS_WORDS) return null

        val modulus = blob.copyOfRange(8, 8 + MODULUS_BYTES)
        val exponent = blob.int(8 + 2 * MODULUS_BYTES)
        val spec = RSAPublicKeySpec(
            BigInteger(1, modulus.reversedArray()),
            BigInteger.valueOf(exponent.toLong() and 0xFFFFFFFFL),
        )
        return runCatching { KeyFactory.getInstance("RSA").generatePublic(spec) }.getOrNull()
    }

    /** True when [signature] is [key]'s PKCS#1 v1.5 signature over [token]. */
    fun verify(token: ByteArray, signature: ByteArray, key: PublicKey): Boolean {
        if (token.size != TOKEN_BYTES) return false
        val encoded = rawPublicOperation(signature, key) ?: return false
        val digestInfo = unpad(encoded) ?: return false
        return MessageDigest.isEqual(digestInfo, SHA1_DIGEST_INFO + token)
    }

    /**
     * `signature ^ e mod n`, left-padded to the modulus length.
     *
     * `NoPadding` rather than `PKCS1Padding` on purpose: JCE providers disagree about which padding
     * block type a *public-key decrypt* is allowed to unwrap, and this code has to behave identically
     * on a desktop JDK and on Android's Conscrypt.
     */
    private fun rawPublicOperation(signature: ByteArray, key: PublicKey): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val raw = cipher.doFinal(signature)
        if (raw.size >= MODULUS_BYTES) {
            raw.copyOfRange(raw.size - MODULUS_BYTES, raw.size)
        } else {
            ByteArray(MODULUS_BYTES).also { raw.copyInto(it, MODULUS_BYTES - raw.size) }
        }
    }.getOrNull()

    /** Strips `0x00 0x01 0xFF… 0x00` from an EMSA-PKCS1-v1_5 block, or null if it is not one. */
    private fun unpad(encoded: ByteArray): ByteArray? {
        if (encoded.size != MODULUS_BYTES) return null
        if (encoded[0] != ZERO || encoded[1] != BLOCK_TYPE_SIGNATURE) return null
        var index = 2
        while (index < encoded.size && encoded[index] == PAD_BYTE) index++
        if (index == 2 || index >= encoded.size || encoded[index] != ZERO) return null
        return encoded.copyOfRange(index + 1, encoded.size)
    }

    private const val ZERO: Byte = 0
    private const val BLOCK_TYPE_SIGNATURE: Byte = 1
    private const val PAD_BYTE: Byte = -1

    private fun ByteArray.int(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * The adb public keys allowed to reach the virtual device: the `adbkey.pub` of the adb client that
 * runs *inside the distro*, and nothing else.
 *
 * The file is probed across the rootfs rather than read from `$HOME`, for the same reason
 * `TerminalSessionManager` probes for the Android SDK: adb keeps its key in the home directory of
 * whichever user ran it, and the user that runs `adb` is not the user that runs a build.
 *
 * Re-read on every connection so a key generated after the daemon started still works.
 */
class AdbAuthorizedKeys(private val distrosDir: File) : () -> List<String> {

    override fun invoke(): List<String> = distrosDir.listFiles().orEmpty()
        .sortedBy(File::getName)
        .flatMap { distro -> keyFilesIn(File(distro, "rootfs")) }
        .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    private fun keyFilesIn(rootfs: File): List<File> =
        (listOf(File(rootfs, "root")) + File(rootfs, "home").listFiles().orEmpty().sortedBy(File::getName))
            .map { home -> File(home, ".android/adbkey.pub") }
            .filter(File::isFile)
}
