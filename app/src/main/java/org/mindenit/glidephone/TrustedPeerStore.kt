package org.mindenit.glidephone

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Certificate pins are device-local and must not survive restoring app data
 * without the Android Keystore identity. Call pin only after both confirmations.
 */
internal class TrustedPeerStore(context: Context, filename: String = "trusted-peers-v1.json") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))

    fun certificate(peerId: String, localId: String): X509Certificate? = synchronized(lock) {
        val record = read().optJSONObject(peerId) ?: return@synchronized null
        check(record.getString("localId") == localId) { "Local identity changed" }
        val encoded = Base64.decode(record.getString("certificate"), Base64.NO_WRAP)
        CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }

    fun pin(peerId: String, identity: LocalIdentity.Identity, certificate: X509Certificate, pairing: PairingState) = synchronized(lock) {
        val localId = identity.id
        require(ID_PATTERN.matches(peerId) && ID_PATTERN.matches(localId))
        check(pairing.status == PairingState.Status.CONFIRMED &&
            pairing.matchesKeys(identity.certificate.publicKey.encoded, certificate.publicKey.encoded)) {
            "Pairing not confirmed for these keys"
        }
        check(certificate.subjectX500Principal.getName("RFC2253").split(',')
            .any { it.trim().equals("CN=$peerId", ignoreCase = true) }) {
            "Peer certificate identity mismatch"
        }
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val records = read()
        val existing = records.optJSONObject(peerId)
        if (existing != null) {
            check(existing.getString("localId") == localId) { "Local identity changed" }
            val previous = Base64.decode(existing.getString("certificate"), Base64.NO_WRAP)
            check(MessageDigest.isEqual(previous, certificate.encoded)) { "Peer certificate changed" }
            return@synchronized
        }
        records.put(peerId, JSONObject()
            .put("localId", localId)
            .put("certificate", Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)))
        write(records)
    }

    /** Last protocol version seen from a trusted peer (0 if unknown). */
    fun protocolVersion(peerId: String): Int = synchronized(lock) {
        read().optJSONObject(peerId)?.optInt("protocolVersion", 0) ?: 0
    }

    fun recordProtocolVersion(peerId: String, version: Int, name: String? = null, type: String? = null) = synchronized(lock) {
        val records = read()
        val record = records.optJSONObject(peerId) ?: return@synchronized
        if (record.optInt("protocolVersion", 0) == version &&
            (name == null || record.optString("name") == name) && (type == null || record.optString("type") == type)) return@synchronized
        record.put("protocolVersion", version)
        // Remembered so paired devices can be listed (and shared to) while offline.
        name?.let { record.put("name", it) }
        type?.let { record.put("type", it) }
        write(records)
    }

    data class Paired(val id: String, val name: String, val type: String, val macs: List<String> = emptyList(),
                      val lastAddress: String? = null)

    /** Where a paired device was last connected from. */
    fun recordAddress(peerId: String, address: String) = synchronized(lock) {
        val records = read()
        val record = records.optJSONObject(peerId) ?: return@synchronized
        if (record.optString("lastAddress") == address) return@synchronized
        record.put("lastAddress", address)
        write(records)
    }

    /** Wake-on-LAN data a Glide desktop sent (glide.wol) and where it was last seen. */
    fun recordWake(peerId: String, macs: List<String>, address: String?) = synchronized(lock) {
        val records = read()
        val record = records.optJSONObject(peerId) ?: return@synchronized
        record.put("macs", org.json.JSONArray(macs))
        address?.let { record.put("lastAddress", it) }
        write(records)
    }

    /** Every device paired with [localId], with the last name it announced. */
    fun paired(localId: String): List<Paired> = synchronized(lock) {
        val records = read()
        records.keys().asSequence().mapNotNull { id ->
            val record = records.optJSONObject(id) ?: return@mapNotNull null
            if (record.optString("localId") != localId) return@mapNotNull null
            val macs = record.optJSONArray("macs")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Paired(id, record.optString("name").ifEmpty { id }, record.optString("type").ifEmpty { "desktop" },
                macs, record.optString("lastAddress").ifEmpty { null })
        }.sortedBy { it.name.lowercase() }.toList()
    }

    /** Forget a device whose certificate we can't present (it is offline): KDE's unpair while unreachable. */
    fun forget(peerId: String, localId: String) = synchronized(lock) {
        val records = read()
        val existing = records.optJSONObject(peerId) ?: return@synchronized
        check(existing.getString("localId") == localId) { "Local identity changed" }
        records.remove(peerId)
        write(records)
    }

    fun remove(peerId: String, localId: String, certificate: X509Certificate) = synchronized(lock) {
        val records = read()
        val existing = records.optJSONObject(peerId) ?: return@synchronized
        check(existing.getString("localId") == localId) { "Local identity changed" }
        val previous = Base64.decode(existing.getString("certificate"), Base64.NO_WRAP)
        check(MessageDigest.isEqual(previous, certificate.encoded)) { "Peer certificate changed" }
        records.remove(peerId)
        write(records)
    }

    private fun write(records: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(records.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun read(): JSONObject {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return JSONObject()
        }
        check(bytes.size <= MAX_STORE_BYTES) { "Peer store is too large" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private companion object {
        val lock = Any()
        val ID_PATTERN = Regex("^[a-zA-Z0-9_-]{32,38}$")
        const val MAX_STORE_BYTES = 512 * 1024
    }
}
