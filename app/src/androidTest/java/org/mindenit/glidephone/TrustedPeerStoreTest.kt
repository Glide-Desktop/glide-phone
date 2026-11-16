package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TrustedPeerStoreTest {
    private val suffix = UUID.randomUUID().toString()
    private val prefix = "glide.store.test.$suffix."
    private val filename = "trusted-test-$suffix.json"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.aliases().toList().filter { it.startsWith(prefix) }.forEach { keystore.deleteEntry(it) }
        File(context.noBackupFilesDir, filename).delete()
        File(context.noBackupFilesDir, "$filename.bak").delete()
    }

    @Test
    fun onlyConfirmedPairWithMatchingKeysCanBePinned() {
        val local = LocalIdentity(prefix + "local.").loadOrCreate()
        val peer = LocalIdentity(prefix + "peer.").loadOrCreate()
        val other = LocalIdentity(prefix + "other.").loadOrCreate()
        val store = TrustedPeerStore(context, filename)
        val pairing = PairingState(100, 0, local.certificate.publicKey.encoded, peer.certificate.publicKey.encoded)

        assertNull(store.certificate(peer.id, local.id))
        expectFailure { store.pin(peer.id, local, peer.certificate, pairing) }
        pairing.confirmLocally(1)
        expectFailure { store.pin(peer.id, local, peer.certificate, pairing) }
        pairing.onPeerResponse(true, 2)
        expectFailure { store.pin(peer.id, local, other.certificate, pairing) }
        store.pin(peer.id, local, peer.certificate, pairing)
        assertArrayEquals(peer.certificate.encoded, TrustedPeerStore(context, filename).certificate(peer.id, local.id)!!.encoded)
        expectFailure { store.certificate(peer.id, other.id) }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
