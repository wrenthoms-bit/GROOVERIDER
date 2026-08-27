package com.delrogue.grooverider.source

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Content-addressed store for decoded audio (spec 3.4). A source is keyed by
 * the SHA-256 of its decoded PCM, so re-importing the same audio is free and
 * produces no duplicate -- and later a Seed can reference a source by hash
 * rather than a fragile file path.
 *
 * M1 uses a JSON index rather than Room: Room's KSP processor is pinned to the
 * exact Kotlin version and is fragile to set up blind. The interface below is
 * what M7 will reimplement on Room once the app is iterating on-device.
 */
class SourceStore(rootDir: File) {

    private val dir = File(rootDir, "sources").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun pcmFile(hash: String) = File(dir, "$hash.pcm")
    fun peaksFile(hash: String) = File(dir, "$hash.peaks")

    // ---- content addressing ---------------------------------------------

    /** SHA-256 over (sampleRate, channels, interleaved float32 LE bytes). */
    fun hashOf(audio: DecodedAudio): String {
        val md = MessageDigest.getInstance("SHA-256")
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(audio.sampleRate).putInt(audio.channels).array()
        md.update(header)
        val bb = ByteBuffer.allocate(audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(audio.samples)
        md.update(bb.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun exists(hash: String): Boolean = pcmFile(hash).exists()

    // ---- pcm file --------------------------------------------------------

    private val magic = 0x47525631 // "GRV1"

    fun writePcm(hash: String, audio: DecodedAudio) {
        DataOutputStream(pcmFile(hash).outputStream().buffered()).use { out ->
            out.writeInt(magic)
            out.writeInt(audio.channels)
            out.writeInt(audio.sampleRate)
            out.writeLong(audio.frames)
            val bb = ByteBuffer.allocate(audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(audio.samples)
            out.write(bb.array())
        }
    }

    fun readPcm(hash: String): DecodedAudio? {
        val f = pcmFile(hash)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != magic) return null
            val channels = inp.readInt()
            val sampleRate = inp.readInt()
            val frames = inp.readLong()
            val count = (frames * channels).toInt()
            val bytes = ByteArray(count * 4)
            inp.readFully(bytes)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(count)
            fb.get(samples)
            return DecodedAudio(samples, channels, sampleRate)
        }
    }

    // ---- index -----------------------------------------------------------

    @Synchronized
    fun list(): List<SourceRecord> {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SourceRecord(
                hash = o.getString("hash"),
                name = o.getString("name"),
                channels = o.getInt("channels"),
                sampleRate = o.getInt("sampleRate"),
                frames = o.getLong("frames"),
                importedAt = o.getLong("importedAt"),
            )
        }
    }

    @Synchronized
    fun upsert(record: SourceRecord) {
        val current = list().filter { it.hash != record.hash } + record
        val arr = JSONArray()
        current.sortedByDescending { it.importedAt }.forEach { r ->
            arr.put(JSONObject().apply {
                put("hash", r.hash); put("name", r.name)
                put("channels", r.channels); put("sampleRate", r.sampleRate)
                put("frames", r.frames); put("importedAt", r.importedAt)
            })
        }
        indexFile.writeText(arr.toString())
    }

    @Synchronized
    fun delete(hash: String) {
        pcmFile(hash).delete(); peaksFile(hash).delete()
        val arr = JSONArray()
        list().filter { it.hash != hash }.forEach { r ->
            arr.put(JSONObject().apply {
                put("hash", r.hash); put("name", r.name)
                put("channels", r.channels); put("sampleRate", r.sampleRate)
                put("frames", r.frames); put("importedAt", r.importedAt)
            })
        }
        indexFile.writeText(arr.toString())
    }

    // ---- waveform cache --------------------------------------------------

    fun writePeaks(hash: String, peaks: FloatArray) {
        DataOutputStream(peaksFile(hash).outputStream().buffered()).use { out ->
            out.writeInt(peaks.size)
            val bb = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(peaks)
            out.write(bb.array())
        }
    }

    fun readPeaks(hash: String): FloatArray? {
        val f = peaksFile(hash)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val n = inp.readInt()
            val bytes = ByteArray(n * 4); inp.readFully(bytes)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(n).also { fb.get(it) }
        }
    }
}
