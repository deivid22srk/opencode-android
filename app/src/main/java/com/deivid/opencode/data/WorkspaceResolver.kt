package com.deivid.opencode.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract

/**
 * Converts a SAF tree URI returned by `ActivityResultContracts.OpenDocumentTree`
 * into a real filesystem path that native code (opencode) can `cd` into.
 *
 * SAF URIs from the system DocumentsProvider look like:
 *
 *   content://com.android.externalstorage.documents/tree/primary%3AMyFolder
 *   content://com.android.externalstorage.documents/tree/1234-5678%3AMyFolder
 *
 * The `tree document ID` is the part after `tree/` (URL-decoded):
 *
 *   primary:MyFolder            ← primary internal storage (/storage/emulated/0/MyFolder)
 *   1234-5678:MyFolder          ← removable SD card (/storage/1234-5678/MyFolder)
 *   home:Documents              ← app-specific "home" on some devices
 *
 * We resolve these by mapping the volume ID to a base directory via
 * [StorageManager.storageVolumes]. Returns null if the URI is not from
 * the external-storage provider or if the volume can't be identified.
 */
object WorkspaceResolver {

    fun resolve(context: Context, uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null

        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        if (parts.isEmpty()) return null

        val volumeId = parts[0]
        val relativePath = if (parts.size > 1) parts[1] else ""

        val baseDir = resolveVolumeBase(context, volumeId) ?: return null
        val path = if (relativePath.isBlank()) baseDir else java.io.File(baseDir, relativePath)
        return path.absolutePath
    }

    private fun resolveVolumeBase(context: Context, volumeId: String): java.io.File? {
        // Fast path: primary internal storage
        if (volumeId == "primary") {
            return Environment.getExternalStorageDirectory()
        }

        // Slow path: ask StorageManager to map the volume UUID to a directory.
        // StorageVolume.directory requires API 30+, but on older devices we
        // can fall back to the well-known mount point pattern.
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return null

        val volume = sm.storageVolumes.firstOrNull { vol ->
            vol.uuid?.equals(volumeId, ignoreCase = true) == true
        } ?: return null

        return volume.directory ?: run {
            // Fallback for API 26-29 where StorageVolume.directory doesn't exist
            // (it was added in API 30). Most SD cards mount at /storage/<uuid>/.
            java.io.File("/storage/$volumeId").takeIf { it.exists() }
        }
    }
}

/**
 * Convenience extension on `StorageVolume` that works on all API levels —
 * `StorageVolume.getDirectory()` is hidden behind API 30 in the SDK.
 */
private val StorageVolume.directory: java.io.File?
    get() = try {
        val m = StorageVolume::class.java.getMethod("getDirectory")
        m.invoke(this) as? java.io.File
    } catch (_: Exception) {
        null
    }
