package org.mindenit.glidephone

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.file.util.BaseFileSystem
import org.apache.sshd.common.file.util.BasePath
import org.apache.sshd.common.session.SessionContext
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

/**
 * Folders picked with the system folder picker, shown over SFTP as top-level directories
 * (KDE Connect's saf/ package) — the way to share files before "all files access" existed.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class SafFileSystemFactory(private val context: Context) : FileSystemFactory {
    private val roots = LinkedHashMap<String, Uri>()
    private val provider = SafFileSystemProvider(context, roots)

    fun setRoots(storages: List<SftpStorage>) {
        roots.clear()
        storages.forEach { roots[it.displayName] = it.uri }
    }

    override fun createFileSystem(session: SessionContext?): FileSystem = SafFileSystem(provider, roots, context)
    override fun getUserHomeDir(session: SessionContext?): Path? = null
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SafPath(fileSystem: SafFileSystem, var safUri: Uri?, root: String?, names: List<String>) :
    BasePath<SafPath, SafFileSystem>(fileSystem, root, names) {
    fun nameList(): List<String> = names
    override fun toRealPath(vararg options: LinkOption?): SafPath = normalize()
    override fun toUri(): URI = URI.create(safUri?.toString() ?: throw IllegalStateException("No document for $this"))
    fun document(context: Context): DocumentFile? = safUri?.let { DocumentFile.fromTreeUri(context, it) }
    fun isRoot() = root == "/" && names.isEmpty()
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SafFileSystem(provider: FileSystemProvider, private val roots: Map<String, Uri>, private val context: Context) :
    BaseFileSystem<SafPath>(provider) {
    override fun close() = throw UnsupportedOperationException("SAF does not support closing")
    override fun isOpen() = true
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix")
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw UnsupportedOperationException()

    private tailrec fun find(doc: DocumentFile, names: List<String>): DocumentFile? {
        if (names.isEmpty()) return doc
        val next = doc.findFile(names.first()) ?: return null
        return find(next, names.drop(1))
    }

    override fun create(root: String?, names: List<String>): SafPath {
        if (root == "/" && names.isEmpty()) return SafPath(this, null, "/", emptyList())
        val top = names.firstOrNull()?.let(roots::get) ?: return SafPath(this, null, root, names)
        if (names.size == 1) return SafPath(this, top, root, names)
        val doc = DocumentFile.fromTreeUri(context, top)?.let { find(it, names.drop(1)) }
        return SafPath(this, doc?.uri, root, names)
    }
}

/** 660, like KDE Connect reports for SAF documents. */
@get:RequiresApi(Build.VERSION_CODES.O)
private val SAF_PERMISSIONS get() = setOf(
    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
)

@RequiresApi(Build.VERSION_CODES.O)
private class SafAttributes(
    private val directory: Boolean, private val file: Boolean, private val size: Long, private val modified: Long
) : PosixFileAttributes {
    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(modified)
    override fun lastAccessTime(): FileTime = FileTime.fromMillis(modified)
    override fun creationTime(): FileTime = FileTime.fromMillis(modified)
    override fun size() = size
    override fun fileKey(): Any? = null
    override fun isDirectory() = directory
    override fun isRegularFile() = file
    override fun isSymbolicLink() = false
    override fun isOther() = false
    override fun owner(): UserPrincipal? = null
    override fun group(): GroupPrincipal? = null
    override fun permissions(): Set<PosixFilePermission> = SAF_PERMISSIONS
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SafFileSystemProvider(private val context: Context, private val roots: Map<String, Uri>) : FileSystemProvider() {
    override fun getScheme() = "saf"
    override fun newFileSystem(uri: URI, env: MutableMap<String, *>?): FileSystem = throw UnsupportedOperationException()
    override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()
    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

    private fun Path.saf(): SafPath = this as? SafPath ?: throw IllegalArgumentException("Not a SAF path: $this")
    private fun exists(path: SafPath) = path.isRoot() || path.document(context)?.exists() == true

    private fun createFile(path: SafPath, failIfExists: Boolean): Uri {
        if (path.isRoot()) throw IOException("Cannot create the root")
        if (exists(path)) {
            if (failIfExists) throw FileAlreadyExistsException(path.toString())
            return path.safUri!!
        }
        val parent = (path.parent as? SafPath)?.document(context) ?: throw IOException("Parent directory does not exist")
        val name = path.nameList().last()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val doc = parent.createFile(mime, name) ?: throw IOException("Failed to create $path")
        path.safUri = doc.uri
        return doc.uri
    }

    override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
        newFileChannel(path, options, *attrs)

    override fun newFileChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
        val p = path.saf()
        if (p.isRoot()) throw IOException("The root is a directory")
        val create = StandardOpenOption.CREATE in options || StandardOpenOption.CREATE_NEW in options
        return when {
            StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options -> {
                if (StandardOpenOption.READ in options) throw IllegalArgumentException("Cannot open for reading and writing")
                if (create) createFile(p, StandardOpenOption.CREATE_NEW in options)
                val doc = p.document(context)?.takeIf { it.exists() } ?: throw NoSuchFileException(p.toString())
                val mode = when {
                    StandardOpenOption.APPEND in options -> "wa"
                    StandardOpenOption.TRUNCATE_EXISTING in options -> "wt"
                    else -> "w"
                }
                ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(doc.uri, mode)!!).channel
            }
            else -> {
                if (create) createFile(p, StandardOpenOption.CREATE_NEW in options)
                val doc = p.document(context)?.takeIf { it.exists() } ?: throw NoSuchFileException(p.toString())
                ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(doc.uri, "r")!!).channel
            }
        }
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val p = dir.saf()
        val children: List<Path> = if (p.isRoot()) {
            roots.map { (name, uri) -> SafPath(p.fileSystem, uri, null, listOf(name)) }
        } else {
            val doc = p.document(context) ?: throw NoSuchFileException(p.toString())
            doc.listFiles().mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                if (child.uri.path?.endsWith(".android_secure") == true) return@mapNotNull null
                SafPath(p.fileSystem, child.uri, null, p.nameList() + name)
            }
        }
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = children.filter(filter::accept).toMutableList().iterator()
            override fun close() = Unit
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        val p = dir.saf()
        if (p.isRoot()) throw IOException("Cannot create the root")
        if (exists(p)) throw FileAlreadyExistsException(p.toString())
        val parent = (p.parent as? SafPath)?.document(context) ?: throw IOException("Parent directory does not exist")
        p.safUri = (parent.createDirectory(p.nameList().last()) ?: throw IOException("Failed to create $p")).uri
    }

    override fun delete(path: Path) {
        val doc = path.saf().document(context) ?: throw NoSuchFileException(path.toString())
        if (!doc.delete()) throw IOException("Failed to delete $path")
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        val from = source.saf().document(context) ?: throw NoSuchFileException(source.toString())
        val to = createFile(target.saf(), false)
        context.contentResolver.openOutputStream(to, "wt")?.use { out ->
            context.contentResolver.openInputStream(from.uri)?.use { it.copyTo(out) }
        } ?: throw IOException("Cannot write $target")
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        val from = source.saf()
        val to = target.saf()
        val sourceUri = from.document(context)?.uri ?: throw NoSuchFileException(from.toString())
        val parentUri = (from.parent as? SafPath)?.document(context)?.uri ?: throw IOException("No parent for $from")
        val destParentUri = (to.parent as? SafPath)?.document(context)?.uri ?: throw IOException("No parent for $to")
        if (parentUri == destParentUri) {
            try {
                DocumentsContract.renameDocument(context.contentResolver, sourceUri, to.nameList().last())?.let {
                    from.safUri = it
                    return
                }
            } catch (_: FileNotFoundException) {
            }
        }
        if (DocumentsContract.getTreeDocumentId(parentUri) == DocumentsContract.getTreeDocumentId(destParentUri)) {
            runCatching { DocumentsContract.moveDocument(context.contentResolver, sourceUri, parentUri, destParentUri) }
                .getOrNull()?.let { from.safUri = it; return }
        }
        copy(from, to, *options)
        delete(from)
    }

    override fun isSameFile(p1: Path, p2: Path): Boolean {
        val a = p1.saf(); val b = p2.saf()
        return a.root == b.root && a.nameList() == b.nameList()
    }

    override fun isHidden(path: Path): Boolean = path.saf().let { !it.isRoot() && it.nameList().last().startsWith(".") }
    override fun getFileStore(path: Path): FileStore? = null

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        val p = path.saf()
        if (p.isRoot()) {
            if (modes.any { it != AccessMode.READ }) throw AccessDeniedException("/")
            return
        }
        val doc = p.document(context)?.takeIf { it.exists() } ?: throw NoSuchFileException(p.toString())
        modes.forEach {
            when (it) {
                AccessMode.READ -> if (!doc.canRead()) throw AccessDeniedException(p.toString())
                AccessMode.WRITE -> if (!doc.canWrite()) throw AccessDeniedException(p.toString())
                AccessMode.EXECUTE -> throw AccessDeniedException(p.toString())
            }
        }
    }

    private fun attributes(p: SafPath): SafAttributes {
        if (p.isRoot()) return SafAttributes(true, false, 0, 0)
        val doc = p.document(context)?.takeIf { it.exists() } ?: throw NoSuchFileException(p.toString())
        return SafAttributes(doc.isDirectory, doc.isFile, doc.length(), doc.lastModified())
    }

    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption?): V? {
        val p = path.saf()
        // Times, owner and permissions can't be changed through SAF; ignore like KDE Connect.
        val view: FileAttributeView = when (type) {
            BasicFileAttributeView::class.java -> object : BasicFileAttributeView {
                override fun name() = "basic"
                override fun readAttributes(): BasicFileAttributes = attributes(p)
                override fun setTimes(m: FileTime?, a: FileTime?, c: FileTime?) = Unit
            }
            PosixFileAttributeView::class.java -> object : PosixFileAttributeView {
                override fun name() = "posix"
                override fun readAttributes(): PosixFileAttributes = attributes(p)
                override fun setTimes(m: FileTime?, a: FileTime?, c: FileTime?) = Unit
                override fun getOwner(): UserPrincipal? = null
                override fun setOwner(owner: UserPrincipal?) = Unit
                override fun setPermissions(perms: MutableSet<PosixFilePermission>?) = Unit
                override fun setGroup(group: GroupPrincipal?) = Unit
            }
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        return view as V
    }

    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption?): A {
        if (type != BasicFileAttributes::class.java && type != PosixFileAttributes::class.java) {
            throw UnsupportedOperationException("readAttributes($type)")
        }
        @Suppress("UNCHECKED_CAST")
        return attributes(path.saf()) as A
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption?): Map<String, Any?> {
        val a = attributes(path.saf())
        return when {
            attributes == "basic" || attributes.startsWith("basic:") -> mapOf(
                "isDirectory" to a.isDirectory, "isRegularFile" to a.isRegularFile, "isSymbolicLink" to false,
                "isOther" to false, "size" to a.size(), "fileKey" to null, "lastModifiedTime" to a.lastModifiedTime(),
                "lastAccessTime" to a.lastAccessTime(), "creationTime" to a.creationTime()
            )
            attributes == "posix" || attributes.startsWith("posix:") -> mapOf(
                "owner" to null, "group" to null, "permissions" to SAF_PERMISSIONS,
                "isDirectory" to a.isDirectory, "isRegularFile" to a.isRegularFile, "isSymbolicLink" to false,
                "isOther" to false, "size" to a.size(), "fileKey" to null, "lastModifiedTime" to a.lastModifiedTime(),
                "lastAccessTime" to a.lastAccessTime(), "creationTime" to a.creationTime()
            )
            else -> throw UnsupportedOperationException("readAttributes($attributes)")
        }
    }

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption?) {
        if (attribute.startsWith("basic:") && attribute.endsWith("Time")) throw UnsupportedOperationException("$attribute is read-only")
    }
}

/** A folder shared over SFTP on Android 8-10 (SftpPlugin.StorageInfo). */
internal data class SftpStorage(val displayName: String, val uri: Uri)

/** SftpSettingsFragment storage list, per device. */
internal object SftpStorageList {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("sftp_storage", Context.MODE_PRIVATE)

    fun get(context: Context, deviceId: String): List<SftpStorage> = runCatching {
        val array = org.json.JSONArray(prefs(context).getString(deviceId, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SftpStorage(o.getString("DisplayName"), Uri.parse(o.getString("Uri")))
        }
    }.getOrDefault(emptyList())

    fun set(context: Context, deviceId: String, list: List<SftpStorage>) {
        val array = org.json.JSONArray()
        list.forEach { array.put(org.json.JSONObject().put("DisplayName", it.displayName).put("Uri", it.uri.toString())) }
        prefs(context).edit().putString(deviceId, array.toString()).apply()
    }

    /** SftpPlugin.getPathsAndNamesForStorageInfoList: (path, name) per storage, nested ones under their parent. */
    fun pathsAndNames(sorted: List<SftpStorage>): List<Pair<String, String>> {
        var parent: SftpStorage? = null
        return sorted.map { current ->
            val p = parent
            val path = if (p != null && current.uri.toString().startsWith(p.uri.toString())) {
                "/" + p.displayName + "/" + current.uri.path.orEmpty().substring(p.uri.path.orEmpty().length)
            } else {
                parent = current
                "/" + current.displayName
            }
            path to current.displayName
        }
    }

    /** SftpPlugin.removeChildren: only top-level folders become roots. */
    fun withoutChildren(sorted: List<SftpStorage>): List<SftpStorage> {
        var parent: SftpStorage? = null
        return sorted.filter { current ->
            val p = parent
            if (p != null && current.uri.toString().startsWith(p.uri.toString())) false else { parent = current; true }
        }
    }
}
