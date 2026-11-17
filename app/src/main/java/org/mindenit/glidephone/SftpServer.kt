package org.mindenit.glidephone

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.file.root.RootedFileSystemProvider
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.File
import java.io.IOException
import java.nio.channels.Channel
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * SFTP/SCP server for the KDE Connect sftp plugin, after SimpleSftpServer with the native
 * file system (Android 11+, "all files access"). The connected computer logs in as user
 * `kdeconnect` with either its pairing key or a one-time password sent over the link.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class SftpServer(private val context: Context, private val deviceKey: PublicKey) {
    private val sshd: SshServer
    private val storageDirs = mutableListOf<Path>()
    /** Below Android 11 there is no all-files access: serve SAF folders instead (SimpleSftpServer). */
    private val saf: SafFileSystemFactory? = if (SftpSupport.nativeFs) null else SafFileSystemFactory(context)
    @Volatile private var passwordHash = ByteArray(0)

    init {
        sshd = SshServer.setUpDefaultServer()
        sshd.fileSystemFactory = saf ?: NativeStorageFileSystemFactory { storageDirs.toList() }
        sshd.keyPairProvider = KeyPairProvider.wrap(hostKey(context))
        sshd.commandFactory = ScpCommandFactory()
        sshd.subsystemFactories = listOf(SftpSubsystemFactory.Builder().apply {
            if (saf == null) withFileSystemAccessor(MediaIndexingAccessor(context))
        }.build())
        sshd.publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
            user == USER && MessageDigest.isEqual(key.encoded, deviceKey.encoded)
        }
        sshd.passwordAuthenticator = PasswordAuthenticator { user, password, _: ServerSession ->
            user == USER && MessageDigest.isEqual(sha256(password), passwordHash)
        }
    }

    val port: Int get() = sshd.port
    val isStarted: Boolean get() = sshd.isStarted

    fun setSafRoots(storages: List<SftpStorage>) {
        saf?.setRoots(storages)
    }

    fun setStorageDirs(paths: List<Path>) {
        storageDirs.clear()
        storageDirs.addAll(paths.map { it.toAbsolutePath().normalize() })
    }

    fun start(): Boolean {
        if (sshd.isStarted) return true
        for (port in 1739..1764) {
            try {
                sshd.port = port
                sshd.start()
                return true
            } catch (_: IOException) {
            }
        }
        return false
    }

    fun stop() {
        runCatching { sshd.stop(true) }
    }

    /** New random password each time browsing starts (RandomHelper.randomString(28)). */
    fun regeneratePassword(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val password = String(CharArray(28) { alphabet[random.nextInt(alphabet.length)] })
        passwordHash = sha256(password)
        return password
    }

    /** Re-index files touched over SFTP so they appear in galleries and file pickers. */
    private class MediaIndexingAccessor(private val context: Context) : SftpFileSystemAccessor {
        private fun index(path: Path?) {
            path ?: return
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(path.toString()), null, null) }
        }
        override fun removeFile(subsystem: SftpSubsystemProxy?, path: Path?, isDirectory: Boolean) {
            super.removeFile(subsystem, path, isDirectory); index(path)
        }
        override fun copyFile(subsystem: SftpSubsystemProxy?, src: Path?, dst: Path?, opts: MutableCollection<CopyOption>?) {
            super.copyFile(subsystem, src, dst, opts); index(dst)
        }
        override fun renameFile(subsystem: SftpSubsystemProxy?, oldPath: Path?, newPath: Path?, opts: MutableCollection<CopyOption>?) {
            super.renameFile(subsystem, oldPath, newPath, opts); index(oldPath); index(newPath)
        }
        override fun closeFile(subsystem: SftpSubsystemProxy?, fileHandle: FileHandle?, file: Path?, handle: String?,
                               channel: Channel?, options: MutableSet<out OpenOption>?) {
            super.closeFile(subsystem, fileHandle, file, handle, channel, options)
            if (options?.contains(StandardOpenOption.WRITE) == true) index(file)
        }
    }

    /**
     * Rooted at "/", but only the storage volumes are served: "/" and "/storage" list just the
     * way down to them, and anything else (the app's own data, /data, /proc, ...) is refused,
     * so a paired computer can't read the host key or edit the trusted devices.
     */
    private class NativeStorageFileSystemFactory(private val dirs: () -> List<Path>) : FileSystemFactory {
        override fun getUserHomeDir(session: SessionContext?): Path? = null
        override fun createFileSystem(session: SessionContext?): FileSystem =
            Provider().newFileSystem(java.nio.file.Paths.get("/"), emptyMap<String, Any>())

        private inner class Provider : RootedFileSystemProvider() {
            private fun under(path: Path): List<Path> {
                val native = java.nio.file.Paths.get(path.toAbsolutePath().normalize().toString())
                return dirs().filter { it.nameCount > native.nameCount && it.startsWith(native) }
            }
            private fun firstUnder(path: Path): Path? = under(path).firstOrNull()?.let { path.fileSystem.getPath(it.toString()) }

            private fun native(path: Path): Path = java.nio.file.Paths.get(path.toAbsolutePath().normalize().toString())
            /** Inside a storage volume. */
            private fun inside(path: Path): Boolean = native(path).let { n -> dirs().any { n.startsWith(it) } }
            /** Inside a volume, or a folder on the way to one ("/", "/storage"). */
            private fun visible(path: Path): Boolean = inside(path) || under(path).isNotEmpty()
            private fun requireInside(path: Path) {
                if (!inside(path)) throw AccessDeniedException(path.toString())
            }
            private fun requireVisible(path: Path) {
                if (!visible(path)) throw AccessDeniedException(path.toString())
            }

            override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
                requireInside(path); return super.newByteChannel(path, options, *attrs)
            }
            override fun newFileChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
                requireInside(path); return super.newFileChannel(path, options, *attrs)
            }
            override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
                requireInside(dir); super.createDirectory(dir, *attrs)
            }
            override fun delete(path: Path) {
                requireInside(path); super.delete(path)
            }
            override fun copy(source: Path, target: Path, vararg options: CopyOption) {
                requireInside(source); requireInside(target); super.copy(source, target, *options)
            }
            override fun move(source: Path, target: Path, vararg options: CopyOption) {
                requireInside(source); requireInside(target); super.move(source, target, *options)
            }
            // Links could point out of the volumes.
            override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) =
                throw AccessDeniedException(link.toString())
            override fun createLink(link: Path, existing: Path) = throw AccessDeniedException(link.toString())
            override fun readSymbolicLink(link: Path): Path {
                requireInside(link); return super.readSymbolicLink(link)
            }
            override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
                requireInside(path); super.setAttribute(path, attribute, value, *options)
            }

            override fun checkAccess(path: Path, vararg modes: AccessMode) {
                requireVisible(path)
                if (AccessMode.WRITE in modes) requireInside(path)
                try {
                    super.checkAccess(path, *modes)
                } catch (e: IOException) {
                    if (AccessMode.WRITE in modes || under(path).isEmpty()) throw e
                }
            }

            override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A = try {
                requireVisible(path)
                super.readAttributes(path, type, *options)
            } catch (e: IOException) {
                super.readAttributes(firstUnder(path) ?: throw e, type, *options)
            }

            override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> = try {
                requireVisible(path)
                super.readAttributes(path, attributes, *options)
            } catch (e: IOException) {
                super.readAttributes(firstUnder(path) ?: throw e, attributes, *options)
            }

            override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> = try {
                // Above the volumes, list only the way down to them (never /data or the like).
                if (!inside(dir)) throw AccessDeniedException(dir.toString())
                super.newDirectoryStream(dir, filter)
            } catch (e: IOException) {
                val depth = dir.toAbsolutePath().normalize().nameCount
                val children = under(dir).map { it.getName(depth).toString() }.distinct().map { dir.resolve(it) }
                if (children.isEmpty()) throw e
                object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> = children.filter(filter::accept).toMutableList().iterator()
                    override fun close() = Unit
                }
            }
        }
    }

    companion object {
        const val USER = "kdeconnect"

        init {
            System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "") // no BouncyCastle
            System.setProperty("org.apache.sshd.common.io.IoServiceFactoryFactory",
                "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory")
            PathUtils.setUserHomeFolderResolver { java.nio.file.Paths.get("/") }
        }

        private fun sha256(text: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        /** RSA host key kept in no-backup storage, like KDE Connect's RsaHelper. */
        @Synchronized
        private fun hostKey(context: Context): KeyPair {
            val file = File(context.noBackupFilesDir, "sftp-host-key")
            runCatching {
                val (pub, priv) = file.readLines()
                val factory = KeyFactory.getInstance("RSA")
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(android.util.Base64.decode(pub, android.util.Base64.NO_WRAP))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(android.util.Base64.decode(priv, android.util.Base64.NO_WRAP)))
                )
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            file.writeText(
                android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP) + "\n" +
                    android.util.Base64.encodeToString(pair.private.encoded, android.util.Base64.NO_WRAP)
            )
            return pair
        }
    }
}

/** Capability checks usable on any API level (the server itself needs Android 11+). */
internal object SftpSupport {
    /** The server runs on Android 8+ (java.nio.file); 11+ serves the whole storage natively. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val nativeFs: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasStorageAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Storage volumes to expose: (path, description). */
    fun storageVolumes(context: Context): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val dir = volume.directory ?: return@mapNotNull null
            dir.path to volume.getDescription(context)
        }
    }
}
