package com.delrogue.grooverider.seed

import java.util.UUID

/**
 * Per-param sensitivity + range (spec 3.6). Sensitivity is the difference
 * between a useful mutate button and a random-patch button: some params
 * tolerate large moves, some destroy the patch if nudged much. Bit position
 * in a Seed's `lockedParams` mask is this enum's ordinal.
 */
enum class MutableParam(val sensitivity: Float, val range: Float, val min: Float, val max: Float) {
    DENSITY(0.25f, 200f, 0.5f, 200f),
    GRAIN_SIZE(0.25f, 2000f, 5f, 2000f),
    POSITION(0.5f, 1f, 0f, 1f),
    DRIFT(0.5f, 4f, -2f, 2f),
    REVERSE_PROB(0.7f, 1f, 0f, 1f),
    PITCH(0.7f, 48f, -24f, 24f),
    TIMING_JITTER(1.0f, 1f, 0f, 1f),
    SIZE_JITTER(1.0f, 1f, 0f, 1f),
    SPRAY(1.0f, 5000f, 0f, 5000f),
    PITCH_SPRAY(1.0f, 24f, 0f, 24f),
    SPREAD(1.0f, 1f, 0f, 1f),
    ;

    fun isLocked(mask: Long): Boolean = (mask shr ordinal) and 1L == 1L
    fun locked(mask: Long, value: Boolean): Long =
        if (value) mask or (1L shl ordinal) else mask and (1L shl ordinal).inv()
}

/** The exploration mechanic (spec 3.6): from any Seed, six sensitivity-weighted children. */
object SeedMutation {

    fun mutateSix(parent: Seed, amount: Float, lockedParams: Long = parent.lockedParams): List<Seed> =
        (0 until 6).map { mutateOne(parent, amount, it, lockedParams) }

    private fun mutateOne(parent: Seed, amount: Float, childIndex: Int, lockedParams: Long): Seed {
        val childSeed = Hash64.splitmix64(parent.masterSeed xor (childIndex * PHI64))
        val g = ParamState.unpack(parent.params)

        fun perturb(p: MutableParam, current: Float): Float {
            if (p.isLocked(lockedParams)) return current
            val sigma = amount * p.sensitivity * p.range
            val delta = Hash64.gaussian(childSeed, p.ordinal) * sigma
            return (current + delta).coerceIn(p.min, p.max)
        }

        val mutated = g.copy(
            density = perturb(MutableParam.DENSITY, g.density),
            grainSizeMs = perturb(MutableParam.GRAIN_SIZE, g.grainSizeMs),
            position = perturb(MutableParam.POSITION, g.position),
            drift = perturb(MutableParam.DRIFT, g.drift),
            reverseProb = perturb(MutableParam.REVERSE_PROB, g.reverseProb),
            pitchSt = perturb(MutableParam.PITCH, g.pitchSt),
            timingJitter = perturb(MutableParam.TIMING_JITTER, g.timingJitter),
            sizeJitter = perturb(MutableParam.SIZE_JITTER, g.sizeJitter),
            sprayMs = perturb(MutableParam.SPRAY, g.sprayMs),
            pitchSpraySt = perturb(MutableParam.PITCH_SPRAY, g.pitchSpraySt),
            spread = perturb(MutableParam.SPREAD, g.spread),
            // output gain is never mutated (sensitivity 0.0, spec 3.6); windowType,
            // outputWidth and chaos params aren't in the spec's sensitivity table.
        )

        return Seed(
            id = UUID.randomUUID().toString(),
            name = SeedNaming.nameFor(childSeed),
            masterSeed = childSeed,
            params = ParamState.pack(mutated),
            modRoutes = parent.modRoutes,
            sourceHash = parent.sourceHash,
            inPointMs = parent.inPointMs,
            outPointMs = parent.outPointMs,
            parentId = parent.id,
            mutationDistance = amount,
            lockedParams = lockedParams,
            favourite = false,
            renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = parent.waveformThumb,
        )
    }
}
