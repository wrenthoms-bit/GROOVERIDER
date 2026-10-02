package com.delrogue.grooverider.source

import android.content.Context
import android.net.Uri
import com.delrogue.grooverider.engine.GrooveriderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

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

    /** A source bundled in the app's assets, decoded like any picked file. */
    suspend fun importAsset(assetName: String, displayName: String): SourceRecord = withContext(Dispatchers.IO) {
        // The decoder reads a Uri, so stage the asset as a file for the moment.
        val staged = File(appContext.cacheDir, "factory-$assetName")
        try {
            appContext.assets.open(assetName).use { input -> staged.outputStream().use { input.copyTo(it) } }
            commit(AudioDecoder.decode(appContext, Uri.fromFile(staged)), displayName)
        } finally {
            staged.delete()
        }
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
        val loaded = GrooveriderEngine.loadSource(audio.samples, audio.channels, audio.sampleRate) > 0
        if (loaded) _engineSourceHash.value = hash
        loaded
    }

    fun delete(hash: String) = store.delete(hash)

    companion object {
        private val _engineSourceHash = MutableStateFlow("")

        /** The source the engine is playing right now, however it got there
         * (picked on the Sources tab, or brought in by loading a Seed). */
        val engineSourceHash: StateFlow<String> = _engineSourceHash.asStateFlow()
    }
}
