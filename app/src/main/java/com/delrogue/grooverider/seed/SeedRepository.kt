package com.delrogue.grooverider.seed

import android.content.Context
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.ui.GrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Persists and applies Seeds (spec 3.3). Applying a Seed is just pushing its
 * masterSeed + unpacked params through the same facade the debug sliders use
 * -- the engine has no separate "load a Seed" code path, which is exactly
 * the point of the determinism contract (spec 3.1).
 */
class SeedRepository(context: Context) {
    private val dao = SeedDatabase.get(context).seedDao()

    fun observeAll(): Flow<List<Seed>> = dao.observeAll()

    suspend fun save(
        masterSeed: Long,
        grain: GrainState,
        sourceHash: String,
        inPointMs: Int = 0,
        outPointMs: Int = 0,
        waveformThumb: ByteArray = ByteArray(0),
        parentId: String? = null,
        mutationDistance: Float = 0f,
    ): Seed = withContext(Dispatchers.IO) {
        val seed = Seed(
            id = UUID.randomUUID().toString(),
            name = SeedNaming.nameFor(masterSeed),
            masterSeed = masterSeed,
            params = ParamState.pack(grain),
            modRoutes = ByteArray(0),
            sourceHash = sourceHash,
            inPointMs = inPointMs,
            outPointMs = outPointMs,
            parentId = parentId,
            mutationDistance = mutationDistance,
            lockedParams = 0L,
            favourite = false,
            renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = waveformThumb,
        )
        dao.upsert(seed)
        seed
    }

    suspend fun rename(seed: Seed, name: String) = withContext(Dispatchers.IO) {
        dao.update(seed.copy(name = name))
    }

    suspend fun update(seed: Seed) = withContext(Dispatchers.IO) { dao.update(seed) }

    suspend fun importSeed(seed: Seed) = withContext(Dispatchers.IO) { dao.upsert(seed) }

    suspend fun setFavourite(seed: Seed, favourite: Boolean) = withContext(Dispatchers.IO) {
        dao.update(seed.copy(favourite = favourite))
    }

    suspend fun delete(seed: Seed) = withContext(Dispatchers.IO) { dao.delete(seed) }

    suspend fun getChildren(seed: Seed): List<Seed> = withContext(Dispatchers.IO) { dao.getChildren(seed.id) }

    suspend fun getById(id: String): Seed? = withContext(Dispatchers.IO) { dao.getById(id) }

    /** "Apply to..." (spec 3.4): the recipe stays intact, only the material changes. */
    suspend fun applyToSource(seed: Seed, newSourceHash: String): Seed = withContext(Dispatchers.IO) {
        val copy = seed.copy(
            id = UUID.randomUUID().toString(),
            sourceHash = newSourceHash,
            parentId = seed.id,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(copy)
        copy
    }

    suspend fun mutateAndSave(parent: Seed, amount: Float, lockedParams: Long = parent.lockedParams): List<Seed> =
        withContext(Dispatchers.IO) {
            val children = SeedMutation.mutateSix(parent, amount, lockedParams)
            children.forEach { dao.upsert(it) }
            children
        }

    suspend fun breedAndSave(parentA: Seed, parentB: Seed, blend: Float): Seed = withContext(Dispatchers.IO) {
        val child = SeedBreeding.breed(parentA, parentB, blend)
        dao.upsert(child)
        child
    }

    suspend fun importSeedFile(json: String): Seed? = withContext(Dispatchers.IO) {
        SeedFileIO.import(json)?.also { dao.upsert(it) }
    }

    /** Pushes a Seed's masterSeed + params into the live engine; returns the unpacked params. */
    fun apply(seed: Seed): GrainState {
        val grain = ParamState.unpack(seed.params)
        GrooveriderEngine.setMasterSeed(seed.masterSeed)
        GrooveriderEngine.setGrainDensity(grain.density)
        GrooveriderEngine.setGrainTimingJitter(grain.timingJitter)
        GrooveriderEngine.setGrainSizeMs(grain.grainSizeMs)
        GrooveriderEngine.setGrainSizeJitter(grain.sizeJitter)
        GrooveriderEngine.setGrainPosition(grain.position)
        GrooveriderEngine.setGrainSprayMs(grain.sprayMs)
        GrooveriderEngine.setGrainDrift(grain.drift)
        GrooveriderEngine.setGrainPitchSt(grain.pitchSt)
        GrooveriderEngine.setGrainPitchSpraySt(grain.pitchSpraySt)
        GrooveriderEngine.setGrainReverseProb(grain.reverseProb)
        GrooveriderEngine.setGrainSpread(grain.spread)
        GrooveriderEngine.setGrainWindowType(grain.windowType)
        GrooveriderEngine.setOutputWidth(grain.outputWidth)
        GrooveriderEngine.setOutputGain(grain.outputGain)
        GrooveriderEngine.setChaosRate(grain.chaosRate)
        GrooveriderEngine.setChaosEnabled(grain.chaosEnabled)
        return grain
    }
}
