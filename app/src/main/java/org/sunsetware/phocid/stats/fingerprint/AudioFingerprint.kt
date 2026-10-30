package org.sunsetware.phocid.stats.fingerprint

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Hash the audio payload only, stripping container tags.
 *
 * This keeps the identity stable when ID3v2 / FLAC / MP4 / WAV / APEv2 / ID3v1 tags are edited. The
 * file length, the first 256 KB, and the last 256 KB of the audio payload are hashed; a full-file
 * hash would be needlessly slow on large libraries.
 *
 * OGG/Opus is intentionally not special-cased: tag edits change page sequence numbers and CRCs
 * there, so [TagKey] and [RelPathKey] are better fallbacks.
 */
object AudioFingerprint {
    private const val WINDOW = 256 * 1024
    private const val HASH_HEX_LEN = 32

    fun compute(file: File): String? = runCatching {
        if (!file.isFile || file.length() < 1024) return null
        FileSource(file).use { source -> compute(source) }
    }
        .getOrNull()

    fun compute(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                FileChannelSource(stream.channel).use { source -> compute(source) }
            }
        }
    }
        .getOrNull()

    fun compute(source: RandomAccessSource): String? = runCatching {
        if (source.size < 1024) return null
        val (start, end) = audioRange(source, source.size)
        val length = end - start
        if (length <= 0) return null

        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(length).array())

        val window = minOf(WINDOW.toLong(), length).toInt()
        val buffer = ByteArray(window)

        source.readFully(start, buffer)
        md.update(buffer)

        if (length > window * 2L) {
            source.readFully(end - window, buffer)
            md.update(buffer)
        }

        md.digest().joinToString("") { "%02x".format(it) }.substring(0, HASH_HEX_LEN)
    }
        .getOrNull()

    /** Returns [audio start, audio end), with container tags removed. */
    private fun audioRange(source: RandomAccessSource, size: Long): Pair<Long, Long> {
        var start = 0L
        var end = size

        val head = ByteArray(16)
        source.readFully(0, head)

        when {
            // ID3v2: 10-byte header + syncsafe size (+10-byte footer)
            head.ascii(0, 3) == "ID3" -> {
                val tagSize =
                    (head[6].i7() shl 21) or
                        (head[7].i7() shl 14) or
                        (head[8].i7() shl 7) or
                        head[9].i7()
                val hasFooter = head[5].toInt() and 0x10 != 0
                start = 10L + tagSize + if (hasFooter) 10 else 0
            }

            // FLAC: walk METADATA_BLOCKs until the last-block flag
            head.ascii(0, 4) == "fLaC" -> {
                var position = 4L
                val block = ByteArray(4)
                while (position + 4 <= size) {
                    source.readFully(position, block)
                    val blockLength = (block[1].u() shl 16) or (block[2].u() shl 8) or block[3].u()
                    val last = block[0].toInt() and 0x80 != 0
                    position += 4 + blockLength
                    if (last) break
                }
                start = position.coerceAtMost(size)
            }

            // MP4/M4A: mdat is the audio payload
            head.ascii(4, 4) == "ftyp" -> {
                findMdat(source, size)?.let {
                    return it
                }
            }

            // WAV: data chunk
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> {
                findWavData(source, size)?.let {
                    return it
                }
            }
        }

        // Trailing ID3v1 (128 bytes)
        if (end - start > 128) {
            val tail = ByteArray(128)
            source.readFully(end - 128, tail)
            if (tail.ascii(0, 3) == "TAG") end -= 128
        }

        // Trailing APEv2 footer (32 bytes, optional header before the tag)
        if (end - start > 32) {
            val ape = ByteArray(32)
            source.readFully(end - 32, ape)
            if (ape.ascii(0, 8) == "APETAGEX") {
                val tagSize = ByteBuffer.wrap(ape, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val flags = ByteBuffer.wrap(ape, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val hasHeader = flags and 0x80000000.toInt() != 0
                end -= tagSize.toLong() + if (hasHeader) 32 else 0
            }
        }

        if (end <= start) return 0L to size
        return start to end
    }

    private fun findMdat(source: RandomAccessSource, size: Long): Pair<Long, Long>? {
        var position = 0L
        val header = ByteArray(8)
        while (position + 8 <= size) {
            source.readFully(position, header)
            var boxSize = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = header.ascii(4, 4)
            var payloadStart = position + 8

            when (boxSize) {
                1L -> {
                    val large = ByteArray(8)
                    source.readFully(position + 8, large)
                    boxSize = ByteBuffer.wrap(large).long
                    payloadStart = position + 16
                }
                0L -> boxSize = size - position
            }
            if (boxSize < 8) return null
            if (type == "mdat") {
                return payloadStart to (position + boxSize).coerceAtMost(size)
            }
            position += boxSize
        }
        return null
    }

    private fun findWavData(source: RandomAccessSource, size: Long): Pair<Long, Long>? {
        var position = 12L
        val header = ByteArray(8)
        while (position + 8 <= size) {
            source.readFully(position, header)
            val type = header.ascii(0, 4)
            val chunkSize =
                ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                    0xFFFFFFFFL
            if (type == "data") {
                val dataStart = position + 8
                return dataStart to (dataStart + chunkSize).coerceAtMost(size)
            }
            position += 8 + chunkSize + (chunkSize and 1L)
        }
        return null
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        if (offset + length > this.size) "" else String(this, offset, length, Charsets.US_ASCII)

    private fun Byte.u(): Int = toInt() and 0xFF

    private fun Byte.i7(): Int = toInt() and 0x7F
}

interface RandomAccessSource : Closeable {
    val size: Long

    fun readFully(position: Long, target: ByteArray)
}

private class FileSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readFully(position: Long, target: ByteArray) {
        raf.seek(position)
        raf.readFully(target)
    }

    override fun close() = raf.close()
}

private class FileChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read =
                channel.read(
                    ByteBuffer.wrap(target, offset, target.size - offset),
                    position + offset,
                )
            if (read <= 0) throw EOFException()
            offset += read
        }
    }

    override fun close() = channel.close()
}
