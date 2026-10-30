package org.sunsetware.phocid.utils

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/**
 * Returns the roots of all shared/external storage volumes.
 *
 * [StorageManager.storageVolumes] is the modern replacement for the deprecated Environment
 * storage-directory API. The app-specific directories are kept as a fallback for devices where a
 * volume does not expose a directory.
 */
fun Context.externalStorageRoots(): List<File> {
    val volumeRoots =
        getSystemService(StorageManager::class.java)
            .storageVolumes
            .asSequence()
            .filter { it.isPrimary || it.isRemovable || it.isEmulated }
            .mapNotNull { it.directory }

    val appRoots =
        getExternalFilesDirs(null).orEmpty().asSequence().mapNotNull {
            it?.parentFile?.parentFile?.parentFile?.parentFile
        }

    return mergeStorageRoots(volumeRoots.toList(), appRoots.toList())
}

fun mergeStorageRoots(volumeRoots: List<File?>, appRoots: List<File?>): List<File> {
    return (volumeRoots + appRoots)
        .filterNotNull()
        .filter { it.isDirectory }
        .distinctBy { it.absolutePath }
}
