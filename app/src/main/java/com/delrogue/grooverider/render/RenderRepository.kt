package com.delrogue.grooverider.render

import android.content.Context
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.seed.SeedDatabase
import com.delrogue.grooverider.seed.SeedNaming
import com.delrogue.grooverider.source.SourceStore
import com.delrogue.grooverider.ui.GrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Turns a live patch into a WAV in Logic (spec 6). Offline render is the
 * deliverable -- realtime is just the instrument (spec 0.3).
 */
class RenderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sourceStore = SourceStore(appContext.filesDir)
    private val renderDao = SeedDatabase.get(appContext).renderDao()
    private val rendersDir = File(appContext.filesDir, "renders").apply { mkdirs() }

    /**
     * Renders [durationSeconds] of the current live patch (masterSeed + grain
     * params + whichever source is loaded) at HQ, writes a WAV with the Seed
     * embedded, and indexes it. `seedId` links the render back to a saved
     * Seed if one exists yet (nullable -- a render doesn't require saving first).
     */
    suspend fun renderCurrent(
        sourceHash: String,
        masterSeed: Long,
        grain: GrainState,
        durationSeconds: Double,
        seamlessLoop: Boolean,
        bpm: Int? = null,
        bars: Int? = null,
        seedId: String? = null,
    ): File = withContext(Dispatchers.Default) {
        val audio = sourceStore.readPcm(sourceHash)
            ?: error("Source $sourceHash not found -- has it been deleted?")

        val dstRate = GrooveriderEngine.engineSampleRate().let { if (it > 0) it else audio.sampleRate }
        val params = floatArrayOf(
            grain.density, grain.timingJitter, grain.grainSizeMs, grain.sizeJitter,
            grain.position, grain.sprayMs, grain.drift, grain.pitchSt, grain.pitchSpraySt,
            grain.reverseProb, grain.spread, grain.outputWidth, grain.outputGain, grain.chaosRate,
        )
        val rendered = GrooveriderEngine.offlineRender(
            pcm = audio.samples, channels = audio.channels, srcRate = audio.sampleRate, dstRate = dstRate,
            params = params, windowType = grain.windowType, chaosEnabled = grain.chaosEnabled,
            masterSeed = masterSeed, durationSeconds = durationSeconds,
            seamlessLoop = seamlessLoop, crossfadeSeconds = 0.05,
        )

        val name = SeedNaming.nameFor(masterSeed)
        val shortHash = sourceHash.take(6)
        val filename = buildString {
            append("GR_").append(name.replace(" ", ""))
            if (bpm != null) append("_").append(bpm).append("bpm")
            if (bars != null) append("_").append(bars).append("bars")
            append("_").append(shortHash).append(".wav")
        }
        val file = File(rendersDir, filename)

        val seedJson = SeedJson.encode(masterSeed, sourceHash, grain)
        val comment = "Grooverider -- Seed \"$name\", source $shortHash, " +
            "${"%.1f".format(durationSeconds)}s" + (bpm?.let { ", ${it}bpm" } ?: "")
        WavWriter.write(file, rendered, dstRate, channels = 2, seedJson = seedJson, comment = comment)

        renderDao.insert(Render(id = UUID.randomUUID().toString(), path = file.absolutePath,
            seedId = seedId ?: "", createdAt = System.currentTimeMillis()))
        file
    }

    /** Round-trip (spec 6.5): re-importing an exported WAV restores the exact patch. */
    fun readEmbeddedSeed(file: File): DecodedSeedJson? =
        WavWriter.readSeedJson(file)?.let { SeedJson.decode(it) }

    fun observeRenders() = renderDao.observeAll()
}
