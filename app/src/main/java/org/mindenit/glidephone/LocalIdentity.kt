package org.mindenit.glidephone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/**
 * The alias carries the device ID, so restoring preferences without the
 * non-exportable key cannot silently restore a trusted identity.
 */
internal class LocalIdentity(private val aliasPrefix: String = "glide.identity.v1.") {
    data class Identity(val id: String, val privateKey: PrivateKey, val certificate: X509Certificate)

    @Synchronized
    fun loadOrCreate(): Identity = synchronized(identityLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = store.aliases().toList().filter { it.startsWith(aliasPrefix) }
        check(aliases.size <= 1) { "Multiple local identities exist" }
        val alias = aliases.singleOrNull() ?: run {
            val id = UUID.randomUUID().toString()
            val newAlias = aliasPrefix + id
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(
                newAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=$id"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
                .setCertificateNotAfter(Date(now + 3650L * 24 * 60 * 60 * 1000))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }.generateKeyPair()
            newAlias
        }
        val id = alias.removePrefix(aliasPrefix)
        check(id.matches(Regex("^[a-zA-Z0-9_-]{32,38}$"))) { "Invalid local device ID" }
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: error("Local certificate is missing")
        check(certificate.subjectX500Principal == X500Principal("CN=$id")) { "Local identity mismatch" }
        certificate.checkValidity()
        val key = store.getKey(alias, null) as? PrivateKey ?: error("Local private key is missing")
        Identity(id, key, certificate)
    }

    private companion object {
        val identityLock = Any()
    }
}
