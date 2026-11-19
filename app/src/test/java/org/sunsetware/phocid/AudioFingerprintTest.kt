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
        assertThat(AudioFingerprint.compute(withTag)).isEqualTo(AudioFingerprint.compute(audioOnly))
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

    @Test
    fun sameHeadAndTailWithDifferentMiddleCollide() {
        // Documents the known collision surface (work plan G1): only the first and last
        // 256 KiB windows of the audio payload are hashed, so two same-length files with
        // identical head and tail windows share the AUDIO_HASH alias and statistics would
        // silently merge. A stronger scheme is deliberately deferred: replacing this hash
        // would invalidate every existing fingerprint.
        val left = temporaryFolder.newFile("left.bin")
        val right = temporaryFolder.newFile("right.bin")
        writeTaggedAudio(left, ByteArray(200 * 1024) { 0x11 })
        writeTaggedAudio(right, ByteArray(200 * 1024) { 0x22 })

        assertThat(AudioFingerprint.compute(left)).isNotNull
        assertThat(AudioFingerprint.compute(right)).isEqualTo(AudioFingerprint.compute(left))
    }

    @Test
    fun flacMetadataEditsDoNotChangeAudioFingerprint() {
        val left = temporaryFolder.newFile("left.flac")
        val right = temporaryFolder.newFile("right.flac")
        writeFlac(left, 0x55)
        writeFlac(right, 0xAA)

        assertThat(AudioFingerprint.compute(left)).isEqualTo(AudioFingerprint.compute(right))
    }

    @Test
    fun mp4ContainerEditsDoNotChangeAudioFingerprint() {
        val left = temporaryFolder.newFile("left.m4a")
        val right = temporaryFolder.newFile("right.m4a")
        writeMp4(left, 0x55)
        writeMp4(right, 0xAA)

        assertThat(AudioFingerprint.compute(left)).isEqualTo(AudioFingerprint.compute(right))
    }

    @Test
    fun wavHeaderEditsDoNotChangeAudioFingerprint() {
        val left = temporaryFolder.newFile("left.wav")
        val right = temporaryFolder.newFile("right.wav")
        writeWav(left)
        writeWav(right)

        assertThat(AudioFingerprint.compute(left)).isEqualTo(AudioFingerprint.compute(right))
    }

    @Test
    fun apev2TagEditsDoNotChangeAudioFingerprint() {
        val left = temporaryFolder.newFile("left.ape")
        val right = temporaryFolder.newFile("right.ape")
        writeApe(left, 0x55)
        writeApe(right, 0xAA)

        assertThat(AudioFingerprint.compute(left)).isEqualTo(AudioFingerprint.compute(right))
    }

    @Test
    fun apev2TagIsFullyStrippedToTheExactAudioRange() {
        // Guards the spec reading: the footer size field includes the footer, so the audio range
        // must end exactly where the tag begins. Over- or under-subtracting shifts the window into
        // the audio (or leaves tag bytes in it) and changes the hash vs the bare audio.
        val tagged = temporaryFolder.newFile("tagged.ape")
        writeApe(tagged, 0x55)
        val bare = temporaryFolder.newFile("bare.ape")
        RandomAccessFile(bare, "rw").use { it.write(ByteArray(4096) { (it * 31).toByte() }) }

        assertThat(AudioFingerprint.compute(tagged)).isEqualTo(AudioFingerprint.compute(bare))
    }

    /** Same-length audio with a small ID3v2 tag; only the middle bytes differ. */
    private fun writeTaggedAudio(file: File, middle: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("ID3".toByteArray(Charsets.US_ASCII))
            raf.write(byteArrayOf(3, 0, 0))
            raf.write(syncSafe(64))
            raf.write(ByteArray(64))
            raf.write(ByteArray(256 * 1024) { (it % 251).toByte() })
            raf.write(middle)
            raf.write(ByteArray(256 * 1024) { ((it * 13) % 251).toByte() })
        }
    }

    private fun writeFlac(file: File, paddingByte: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("fLaC".toByteArray(Charsets.US_ASCII))
            // A padding metadata block whose content differs, then the final empty block.
            raf.write(byteArrayOf(0x00, 0x00, 0x00, 0x10))
            raf.write(ByteArray(16) { paddingByte.toByte() })
            raf.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00))
            raf.write(ByteArray(4096) { (it * 31).toByte() })
        }
    }

    private fun writeMp4(file: File, brandByte: Int) {
        val audio = ByteArray(4096) { (it * 31).toByte() }
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(intBytes(24))
            raf.write("ftyp".toByteArray(Charsets.US_ASCII))
            raf.write(ByteArray(16) { brandByte.toByte() })
            raf.write(intBytes(8 + audio.size))
            raf.write("mdat".toByteArray(Charsets.US_ASCII))
            raf.write(audio)
        }
    }

    private fun writeWav(file: File) {
        // WAV is a little-endian container: writing big-endian sizes here would make the chunk
        // walker skip past EOF and fall back to hashing the whole file, including the header.
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intBytesLe(12 + 8 + 16 + 8 + 4096))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.write(intBytesLe(16))
            // Format fields differ per file, like a real re-encode header would.
            raf.write(ByteArray(16) { 0x11 })
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intBytesLe(4096))
            raf.write(ByteArray(4096) { (it * 31).toByte() })
        }
    }

    private fun writeApe(file: File, tagByte: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(ByteArray(4096) { (it * 31).toByte() })
            raf.write(ByteArray(64) { tagByte.toByte() })
            // APEv2 footer: per spec the size field covers the 64-byte tag body PLUS this 32-byte
            // footer (= 96), excluding the optional header; no header flag is set here.
            raf.write("APETAGEX".toByteArray(Charsets.US_ASCII))
            raf.write(intBytes(2000))
            raf.write(intBytesLe(96))
            raf.write(intBytesLe(1))
            raf.write(intBytesLe(0))
            raf.write(ByteArray(8))
        }
    }

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun intBytesLe(value: Int): ByteArray =
        byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        )
}
