package org.sunsetware.phocid

import java.io.File
import java.io.RandomAccessFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sunsetware.phocid.stats.fingerprint.AudioFingerprint

class AudioFingerprintTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun id3TagEditsDoNotChangeAudioFingerprint() {
        val audioOnly = temporaryFolder.newFile("audio.bin")
        val withTag = temporaryFolder.newFile("tagged.bin")
        writeTrack(audioOnly, null)
        writeTrack(withTag, ByteArray(512) { (it * 7).toByte() })

        assertThat(AudioFingerprint.compute(audioOnly)).isNotNull
        assertThat(AudioFingerprint.compute(withTag))
            .isEqualTo(AudioFingerprint.compute(audioOnly))
    }

    private fun writeTrack(file: File, tag: ByteArray?) {
        RandomAccessFile(file, "rw").use { raf ->
            if (tag != null) {
                raf.write("ID3".toByteArray(Charsets.US_ASCII))
                raf.write(byteArrayOf(3, 0, 0))
                raf.write(syncSafe(tag.size))
                raf.write(tag)
            }
            raf.write(ByteArray(4096) { (it * 31).toByte() })
            raf.write("TAG".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(125))
        }
    }

    private fun syncSafe(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 21 and 0x7f).toByte(),
            (value ushr 14 and 0x7f).toByte(),
            (value ushr 7 and 0x7f).toByte(),
            (value and 0x7f).toByte(),
        )
}
