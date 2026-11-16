package org.mindenit.glidephone

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/** Exposes only the app's own certificate and non-exportable key to TLS. */
internal class IdentityKeyManager(private val identity: LocalIdentity.Identity) : X509KeyManager {
    private val alias = "local"

    private fun accepts(keyType: String?): Boolean {
        if (keyType == null) return false
        val type = keyType.substringBefore('_').uppercase()
        val algo = identity.privateKey.algorithm.uppercase()
        return type == algo || (algo == "EC" && type == "ECDSA") || (algo == "ECDSA" && type == "EC")
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (accepts(keyType)) arrayOf(alias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType.isNullOrEmpty() || keyType.any(::accepts)) alias else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (accepts(keyType)) arrayOf(alias) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType == null || accepts(keyType)) alias else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) identity.privateKey else null
}
