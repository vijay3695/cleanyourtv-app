package tv.cleanyour.android.platform

import android.content.ContentResolver
import android.net.Uri
import tv.cleanyour.core.model.ApkInfo
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.trust.ApkInspector
import tv.cleanyour.core.store.BlobStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies one user-selected APK into the app's verified content-addressed cache and inspects it.
 *
 * The picker grants access only to the selected document. No storage permission is requested.
 * The returned BlobRef is safe to pass to TvSession.push: the cache verifies both its size and
 * digest before publishing it, and PackageManager independently parses the APK archive.
 */
class ApkTransferStager(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val blobs: BlobStore,
    private val inspector: ApkInspector,
    private val maxBytes: Long = MAX_APK_BYTES,
) {
    data class StagedApk(val blob: BlobRef, val info: ApkInfo)

    suspend fun stage(uri: Uri): StagedApk = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) {
            "Choose an APK from the system file picker."
        }
        if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
            throw IOException("Cannot create the private APK staging directory.")
        }
        val temporary = File(cacheDir, "apk-${UUID.randomUUID()}.part")
        var staged: BlobRef? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val input = resolver.openInputStream(uri) ?: throw IOException("The selected file could not be opened.")
            input.use { source ->
                FileOutputStream(temporary).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > maxBytes) throw IOException("APK is larger than the 200 MiB transfer limit.")
                        digest.update(buffer, 0, count)
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            if (size == 0L) throw IOException("The selected file is empty.")
            val sha = Sha256(digest.digest().joinToString("") { "%02x".format(it) })
            staged = blobs.put(sha, FileInputStream(temporary), size)
            val info = inspector.inspect(staged) ?: throw IOException("Android could not read a single-signer APK from this file.")
            if (info.sha256 != sha || info.size != size) throw IOException("The APK changed while it was being inspected.")
            StagedApk(staged, info)
        } catch (failure: Throwable) {
            staged?.let { runCatching { blobs.delete(it.sha256) } }
            throw failure
        } finally {
            temporary.delete()
        }
    }

    suspend fun discard(apk: StagedApk) {
        blobs.delete(apk.blob.sha256)
    }

    companion object {
        const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }
}
