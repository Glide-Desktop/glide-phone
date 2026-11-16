package org.mindenit.glidephone

import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import javax.security.auth.x500.X500Principal
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdentityTest {
    private val prefix = "glide.test." + UUID.randomUUID().toString() + "."

    @After
    fun cleanup() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().filter { it.startsWith(prefix) }.forEach { store.deleteEntry(it) }
    }

    @Test
    fun identityPersistsAndCertificateMatchesPrivateKey() {
        val first = LocalIdentity(prefix).loadOrCreate()
        val second = LocalIdentity(prefix).loadOrCreate()

        assertEquals(first.id, second.id)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
        assertEquals(X500Principal("CN=${first.id}"), first.certificate.subjectX500Principal)
        assertNull(first.privateKey.encoded)
        assertNotNull(first.certificate.publicKey.encoded)

        val manager = IdentityKeyManager(first)
        val alias = manager.chooseClientAlias(arrayOf("RSA", "EC"), null, null)
        assertNotNull(alias)
        assertEquals(first.privateKey, manager.getPrivateKey(alias))
        assertArrayEquals(first.certificate.encoded, manager.getCertificateChain(alias)!!.single().encoded)
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.getPrivateKey("other"))

        val data = "Glide Phone".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(first.privateKey)
            update(data)
        }.sign()
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(second.certificate)
            update(data)
        }
        assertTrue(verifier.verify(signature))
    }
}
