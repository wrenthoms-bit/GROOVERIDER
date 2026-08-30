package com.delrogue.grooverider.source

import android.content.Context
import android.net.Uri
import com.delrogue.grooverider.engine.GrooveriderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the source pipeline: decode/capture -> hash -> dedup -> store ->
 * waveform -> index -> hand to the engine for preview. All disk/decoding work
 * runs off the main thread.
 */
class SourceRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = SourceStore(appContext.filesDir)

    fun list(): List<SourceRecord> = store.list()
    fun peaks(hash: String): FloatArray = store.readPeaks(hash) ?: FloatArray(0)

    /** Import a picked file. Returns the resulting record (existing or new). */
    suspend fun importAudio(uri: Uri, displayName: String): SourceRecord = withContext(Dispatchers.IO) {
        val audio = AudioDecoder.decode(appContext, uri)
        commit(audio, displayName)
    }

    /** Onboarding's demo source (spec M8): no file picker, no permission,
     * straight into a texture within seconds of a clean install. */
    suspend fun importSynthetic(samples: FloatArray, channels: Int, sampleRate: Int, name: String): SourceRecord =
        withContext(Dispatchers.IO) { commit(DecodedAudio(samples, channels, sampleRate), name) }

    /** Persist a finished mic take pulled from the engine's recorder. */
    suspend fun commitMicTake(name: String): SourceRecord? = withContext(Dispatchers.IO) {
        val pcm = GrooveriderEngine.micExtract()
        val ch = GrooveriderEngine.micChannels().coerceAtLeast(1)
        val sr = GrooveriderEngine.micSampleRate()
        if (pcm.isEmpty() || sr <= 0) return@withContext null
        commit(DecodedAudio(pcm, ch, sr), name)
    }

    private fun commit(audio: DecodedAudio, name: String): SourceRecord {
        val hash = store.hashOf(audio)
        if (!store.exists(hash)) {                       // dedup: skip all work if seen
            store.writePcm(hash, audio)
            store.writePeaks(hash, Waveform.peaks(audio.samples, audio.channels, 2048))
        }
        val record = SourceRecord(
            hash = hash, name = name, channels = audio.channels,
            sampleRate = audio.sampleRate, frames = audio.frames,
            importedAt = System.currentTimeMillis(),
        )
        store.upsert(record)                             // refresh name/importedAt, still one row
        return record
    }

    /** Load a stored source into the engine (resampled to the engine rate).
     * False if the source is missing on disk, or the native engine hasn't
     * been created yet (loadSource returns 0 frames in that case). */
    suspend fun loadIntoEngine(hash: String): Boolean = withContext(Dispatchers.IO) {
        val audio = store.readPcm(hash) ?: return@withContext false
        GrooveriderEngine.loadSource(audio.samples, audio.channels, audio.sampleRate) > 0
    }

    fun delete(hash: String) = store.delete(hash)
}
