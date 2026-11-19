package org.sunsetware.phocid

import java.io.File
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.data.loadCbor
import org.sunsetware.phocid.data.saveCbor

@RunWith(RobolectricTestRunner::class)
class SaveManagerTest {
    @Serializable
    data class Payload(val value: Int)

    @Test
    fun saveIsAtomicAndKeepsABackup() {
        val context = RuntimeEnvironment.getApplication()

        saveCbor(context, "save_manager_test.bin", false, Payload(1))
        saveCbor(context, "save_manager_test.bin", false, Payload(2))

        // The main file holds the latest value, the backup the previous one, and no temp file
        // is left behind after the atomic rename.
        assertThat(loadCbor<Payload>(context, "save_manager_test.bin", false)?.value).isEqualTo(2)
        assertThat(loadCbor<Payload>(context, "save_manager_test.bin.bak", false)?.value)
            .isEqualTo(1)
        assertThat(File(context.filesDir, "save_manager_test.bin.tmp").exists()).isFalse()
    }

    @Test
    fun loadFallsBackToTheBackupWhenTheMainFileIsUnreadable() {
        val context = RuntimeEnvironment.getApplication()

        saveCbor(context, "save_manager_test2.bin", false, Payload(1))
        saveCbor(context, "save_manager_test2.bin", false, Payload(2))
        File(context.filesDir, "save_manager_test2.bin").delete()

        // A missing main file used to bypass the backup entirely and return null.
        assertThat(loadCbor<Payload>(context, "save_manager_test2.bin", false)?.value).isEqualTo(1)
    }
}
