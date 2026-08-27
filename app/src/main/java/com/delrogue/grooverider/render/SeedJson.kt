package com.delrogue.grooverider.render

import com.delrogue.grooverider.ui.GrainState
import org.json.JSONObject

/** What survives inside a WAV's `grvr` chunk (spec 6.5): the whole recipe. */
data class DecodedSeedJson(
    val masterSeed: Long,
    val sourceHash: String,
    val grain: GrainState,
)

/**
 * The Seed, as JSON, embedded in every exported WAV (spec 6.5). Dropping the
 * file back into Grooverider reads this and restores the exact patch --
 * Logic and every other DAW ignore unknown chunks harmlessly.
 */
object SeedJson {

    fun encode(masterSeed: Long, sourceHash: String, grain: GrainState): String =
        JSONObject().apply {
            put("masterSeed", masterSeed)
            put("sourceHash", sourceHash)
            put("density", grain.density)
            put("timingJitter", grain.timingJitter)
            put("grainSizeMs", grain.grainSizeMs)
            put("sizeJitter", grain.sizeJitter)
            put("position", grain.position)
            put("sprayMs", grain.sprayMs)
            put("drift", grain.drift)
            put("pitchSt", grain.pitchSt)
            put("pitchSpraySt", grain.pitchSpraySt)
            put("reverseProb", grain.reverseProb)
            put("spread", grain.spread)
            put("windowType", grain.windowType)
            put("outputWidth", grain.outputWidth)
            put("outputGain", grain.outputGain)
            put("chaosRate", grain.chaosRate)
            put("chaosEnabled", grain.chaosEnabled)
        }.toString()

    /** Returns null rather than throwing on anything malformed or foreign. */
    fun decode(json: String): DecodedSeedJson? = runCatching {
        val o = JSONObject(json)
        DecodedSeedJson(
            masterSeed = o.getLong("masterSeed"),
            sourceHash = o.optString("sourceHash", ""),
            grain = GrainState(
                density = o.optDouble("density", 40.0).toFloat(),
                timingJitter = o.optDouble("timingJitter", 0.15).toFloat(),
                grainSizeMs = o.optDouble("grainSizeMs", 400.0).toFloat(),
                sizeJitter = o.optDouble("sizeJitter", 0.25).toFloat(),
                position = o.optDouble("position", 0.5).toFloat(),
                sprayMs = o.optDouble("sprayMs", 250.0).toFloat(),
                drift = o.optDouble("drift", 0.05).toFloat(),
                pitchSt = o.optDouble("pitchSt", 0.0).toFloat(),
                pitchSpraySt = o.optDouble("pitchSpraySt", 0.15).toFloat(),
                reverseProb = o.optDouble("reverseProb", 0.2).toFloat(),
                spread = o.optDouble("spread", 0.8).toFloat(),
                windowType = o.optInt("windowType", 0),
                outputWidth = o.optDouble("outputWidth", 1.0).toFloat(),
                outputGain = o.optDouble("outputGain", 0.9).toFloat(),
                chaosRate = o.optDouble("chaosRate", 0.3).toFloat(),
                chaosEnabled = o.optBoolean("chaosEnabled", true),
            ),
        )
    }.getOrNull()
}
