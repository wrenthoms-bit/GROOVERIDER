package com.delrogue.grooverider.render

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 32-bit float WAV with a custom `grvr` RIFF chunk carrying the Seed as JSON,
 * plus a LIST/INFO/ICMT human-readable comment (spec 6.5). Float rather than
 * 24-bit because the output stage's soft saturation sits at -3 dBFS and float
 * leaves headroom decisions to Logic. Unknown chunks are ignored harmlessly
 * by every DAW, so this costs nothing.
 */
object WavWriter {

    fun write(file: File, interleaved: FloatArray, sampleRate: Int, channels: Int, seedJson: String, comment: String) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(12))   // RIFF header placeholder, patched below

            writeFmtChunk(raf, sampleRate, channels)
            writeChunk(raf, "grvr", seedJson.toByteArray(Charsets.UTF_8))
            writeListInfoChunk(raf, comment)
            writeChunk(raf, "data", floatBytes(interleaved))

            val fileLength = raf.length()
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(leInt((fileLength - 8).toInt()))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        }
    }

    /** Reads the embedded Seed JSON back out, or null if this isn't one of ours. */
    fun readSeedJson(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return null
            val riff = ByteArray(4).also { raf.readFully(it) }
            if (String(riff, Charsets.US_ASCII) != "RIFF") return null
            raf.skipBytes(4)   // riff size
            val wave = ByteArray(4).also { raf.readFully(it) }
            if (String(wave, Charsets.US_ASCII) != "WAVE") return null

            while (raf.filePointer + 8 <= raf.length()) {
                val id = ByteArray(4).also { raf.readFully(it) }
                val size = readLeInt(raf)
                val idStr = String(id, Charsets.US_ASCII)
                if (idStr == "grvr") {
                    val body = ByteArray(size).also { raf.readFully(it) }
                    return String(body, Charsets.UTF_8)
                }
                raf.skipBytes(size + (size % 2))   // chunks are word-aligned
            }
        }
        return null
    }

    private fun writeFmtChunk(raf: RandomAccessFile, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 4
        val blockAlign = channels * 4
        val body = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(3)                       // WAVE_FORMAT_IEEE_FLOAT
        body.putShort(channels.toShort())
        body.putInt(sampleRate)
        body.putInt(byteRate)
        body.putShort(blockAlign.toShort())
        body.putShort(32)                      // bits per sample
        writeChunk(raf, "fmt ", body.array())
    }

    private fun writeListInfoChunk(raf: RandomAccessFile, comment: String) {
        val commentBytes = comment.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val icmtSize = 8 + commentBytes.size + (commentBytes.size % 2)
        val body = ByteBuffer.allocate(4 + icmtSize).order(ByteOrder.LITTLE_ENDIAN)
        body.put("INFO".toByteArray(Charsets.US_ASCII))
        body.put("ICMT".toByteArray(Charsets.US_ASCII))
        body.putInt(commentBytes.size)
        body.put(commentBytes)
        if (commentBytes.size % 2 != 0) body.put(0)
        writeChunk(raf, "LIST", body.array())
    }

    private fun writeChunk(raf: RandomAccessFile, id: String, body: ByteArray) {
        raf.write(id.toByteArray(Charsets.US_ASCII))
        raf.write(leInt(body.size))
        raf.write(body)
        if (body.size % 2 != 0) raf.write(0)
    }

    private fun floatBytes(interleaved: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(interleaved.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(interleaved)
        return buf.array()
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4).also { raf.readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
