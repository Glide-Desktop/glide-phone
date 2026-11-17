package org.mindenit.glidephone

import java.security.MessageDigest
import java.util.Locale

/** The v8 verification code is displayed on both peers before confirming pairing. */
internal object PairingVerification {
    fun code(publicKeyA: ByteArray, publicKeyB: ByteArray, timestampSeconds: Long): String {
        require(publicKeyA.isNotEmpty() && publicKeyB.isNotEmpty())
        require(timestampSeconds >= 0)
        val order = compareUnsigned(publicKeyA, publicKeyB)
        val digest = MessageDigest.getInstance("SHA-256")
        if (order >= 0) {
            digest.update(publicKeyA)
            digest.update(publicKeyB)
        } else {
            digest.update(publicKeyB)
            digest.update(publicKeyA)
        }
        digest.update(timestampSeconds.toString().toByteArray(Charsets.UTF_8))
        return digest.digest().take(4).joinToString("") {
            "%02X".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    /** Protocol v7 verification key: the same hash without the timestamp. */
    fun codeV7(publicKeyA: ByteArray, publicKeyB: ByteArray): String {
        require(publicKeyA.isNotEmpty() && publicKeyB.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        if (compareUnsigned(publicKeyA, publicKeyB) >= 0) {
            digest.update(publicKeyA); digest.update(publicKeyB)
        } else {
            digest.update(publicKeyB); digest.update(publicKeyA)
        }
        return digest.digest().take(4).joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    /**
     * Matches Glide Phone's short_verification_code: first 8 hex characters (uppercase)
     * of the peer's SHA-256 certificate fingerprint.
     */
    fun certificateCode(certificateBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateBytes)
        return digest.take(4).joinToString("") {
            "%02X".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    fun certificateCode(certificate: java.security.cert.X509Certificate): String =
        certificateCode(certificate.encoded)

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val comparison = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }
}
