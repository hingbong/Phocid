package org.sunsetware.phocid

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.mergeStorageRoots

class StorageRootsTest {
    @Test
    fun mergeStorageRoots_deduplicatesAndDropsMissingDirectories() {
        val rootA = Files.createTempDirectory("phocid-root-a").toFile()
        val rootB = Files.createTempDirectory("phocid-root-b").toFile()
        val missing = File(rootA, "missing")
        try {
            val merged =
                mergeStorageRoots(
                    volumeRoots = listOf(rootA, rootA, null),
                    appRoots = listOf(rootB, missing, null),
                )

            assertThat(merged).containsExactly(rootA, rootB)
        } finally {
            rootA.deleteRecursively()
            rootB.deleteRecursively()
        }
    }

    @Test
    fun mergeStorageRoots_emptyInputsProduceEmptyList() {
        assertThat(mergeStorageRoots(emptyList(), listOf(null))).isEmpty()
    }
}
