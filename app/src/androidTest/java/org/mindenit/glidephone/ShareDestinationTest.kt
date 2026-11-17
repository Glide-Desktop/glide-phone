package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID

class ShareDestinationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "glide.share.test." + UUID.randomUUID() + "."
    private var saved: android.net.Uri? = null

    @After
    fun cleanup() {
        SetupPreferences(context).shareDestination = null
        saved?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().filter { it.startsWith(prefix) }.forEach { store.deleteEntry(it) }
    }

    @Test
    fun aRevokedCustomFolderFallsBackToDownloads() {
        SetupPreferences(context).shareDestination =
            "content://com.android.externalstorage.documents/tree/primary%3Agone-${UUID.randomUUID()}"
        val sender = LocalIdentity(prefix + "s.").loadOrCreate()
        val receiver = LocalIdentity(prefix + "r.").loadOrCreate()
        val bytes = ByteArray(1234) { (it % 7).toByte() }
        val handle = SharePayload.serve(sender, receiver.certificate, ByteArrayInputStream(bytes), bytes.size.toLong())
        val received = SharePayload.receive(context, receiver, sender.certificate, InetAddress.getByName("127.0.0.1"),
            handle.port, bytes.size.toLong(), "glide-share-test-${UUID.randomUUID()}.bin")
        assertNotNull(received)
        saved = received!!.uri
        val back = context.contentResolver.openInputStream(saved!!)!!.use { it.readBytes() }
        assertArrayEquals(bytes, back)
    }
}
