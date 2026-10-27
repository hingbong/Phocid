package org.sunsetware.phocid

import java.io.File
import java.io.RandomAccessFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sunsetware.phocid.stats.fingerprint.AudioFingerprint
import org.sunsetware.phocid.stats.fingerprint.RandomAccessSource

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

    @Test
    fun contentSourceProducesSameFingerprintAsFile() {
        val audioOnly = temporaryFolder.newFile("audio.bin")
        writeTrack(audioOnly, null)
        val bytes = audioOnly.readBytes()
        val source =
            object : RandomAccessSource {
                override val size: Long = bytes.size.toLong()

                override fun readFully(position: Long, target: ByteArray) {
                    bytes.copyInto(
                        destination = target,
                        destinationOffset = 0,
                        startIndex = position.toInt(),
                        endIndex = position.toInt() + target.size,
                    )
                }

                override fun close() {}
            }

        assertThat(AudioFingerprint.compute(source)).isEqualTo(AudioFingerprint.compute(audioOnly))
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
