package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import org.json.JSONObject

/**
 * The seed file both apps read and write (`.grvr`, described in core/README.md):
 * the master seed and every setting the engine is given, by name. A seed saved
 * on the phone opens in the web app and the other way round.
 *
 * Plain JSON and nothing Android-specific, so it is unit-tested against the same
 * fixture the web app's tests load (core/tests/fixtures).
 */
object SeedShareFormat {
    const val FORMAT = "grooverider-seed"
    const val VERSION = 1

    /** A seed as a file carries it: no database id, and its sound named rather than stored. */
    data class Shared(
        val name: String,
        val masterSeed: Long,
        val grain: GrainState,
        val sourceName: String,
        val sourceHash: String,      // the phone's content hash; empty in files from the web
    )

    // Window ids as Seeds store them (0 Gaussian, 1 Tukey, 2 Hann); the file names them instead.
    private val WINDOW_NAMES = listOf("gaussian", "tukey", "hann")

    fun encode(shared: Shared): String {
        val g = shared.grain
        val params = JSONObject().apply {
            put("density", g.density.toDouble()); put("grainMs", g.grainSizeMs.toDouble())
            put("timingJitter", g.timingJitter.toDouble()); put("sizeJitter", g.sizeJitter.toDouble())
            put("position", g.position.toDouble()); put("sprayMs", g.sprayMs.toDouble())
            put("reverse", g.reverseProb.toDouble()); put("spread", g.spread.toDouble())
            put("window", WINDOW_NAMES.getOrElse(g.windowType) { "gaussian" })
            put("width", g.outputWidth.toDouble()); put("gain", g.outputGain.toDouble())
            put("chaos", g.chaos.toDouble()); put("pitch", g.pitchAmount.toDouble())
            put("key", g.key); put("scale", g.scale); put("register", g.register.toDouble())
            put("detune", g.detune.toDouble()); put("drone", g.drone)
            put("space", g.space.toDouble()); put("shimmer", g.shimmer.toDouble())
            put("tone", g.tone.toDouble()); put("scan", g.scan.toDouble())
        }
        // what only the plain grain engine uses; the web has no such engine and maps these as best it can
        val plain = JSONObject().apply {
            put("drift", g.drift.toDouble()); put("pitchSt", g.pitchSt.toDouble())
            put("pitchSpraySt", g.pitchSpraySt.toDouble())
            put("chaosRate", g.chaosRate.toDouble()); put("chaosEnabled", g.chaosEnabled)
        }
        return JSONObject().apply {
            put("format", FORMAT); put("version", VERSION)
            put("name", shared.name)
            put("masterSeed", "%016x".format(shared.masterSeed))     // text: 64 bits do not survive a JavaScript number
            put("source", JSONObject().apply { put("name", shared.sourceName); put("hash", shared.sourceHash) })
            put("observatory", g.observatory)
            put("params", params); put("plain", plain)
        }.toString(2)
    }

    /** Null if [text] is not a seed file this version can read. Missing settings take their defaults. */
    fun decode(text: String): Shared? = runCatching {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("version", 0) > VERSION) return null
        val p = o.getJSONObject("params")
        val plain = o.optJSONObject("plain") ?: JSONObject()
        val d = GrainState()
        fun f(obj: JSONObject, key: String, fallback: Float) = obj.optDouble(key, fallback.toDouble()).toFloat()
        val observatory = o.optBoolean("observatory", true)
        val scan = f(p, "scan", d.scan)
        val grain = GrainState(
            density = f(p, "density", d.density).coerceIn(0.5f, 200f),
            timingJitter = f(p, "timingJitter", d.timingJitter).coerceIn(0f, 1f),
            grainSizeMs = f(p, "grainMs", d.grainSizeMs).coerceIn(5f, 2000f),
            sizeJitter = f(p, "sizeJitter", d.sizeJitter).coerceIn(0f, 1f),
            position = f(p, "position", d.position).coerceIn(0f, 1f),
            sprayMs = f(p, "sprayMs", d.sprayMs).coerceIn(0f, 5000f),
            reverseProb = f(p, "reverse", d.reverseProb).coerceIn(0f, 1f),
            spread = f(p, "spread", d.spread).coerceIn(0f, 1f),
            windowType = WINDOW_NAMES.indexOf(p.optString("window", "gaussian")).coerceAtLeast(0),
            outputWidth = f(p, "width", d.outputWidth).coerceIn(0f, 2f),
            outputGain = f(p, "gain", d.outputGain).coerceIn(0f, 1.5f),
            // a web seed has no plain-engine settings: its scan stands in for drift, and First Light stays off
            drift = f(plain, "drift", scan).coerceIn(-2f, 2f),
            pitchSt = f(plain, "pitchSt", 0f).coerceIn(-24f, 24f),
            pitchSpraySt = f(plain, "pitchSpraySt", 0f).coerceIn(0f, 24f),
            chaosRate = f(plain, "chaosRate", d.chaosRate).coerceIn(0f, 1f),
            chaosEnabled = plain.optBoolean("chaosEnabled", !observatory),
            observatory = observatory,
            chaos = f(p, "chaos", d.chaos).coerceIn(0f, 1f),
            pitchAmount = f(p, "pitch", d.pitchAmount).coerceIn(0f, 1f),
            key = p.optInt("key", d.key).coerceIn(0, 11),
            scale = p.optInt("scale", d.scale).coerceIn(0, 6),
            register = f(p, "register", d.register).coerceIn(-24f, 24f),
            detune = f(p, "detune", d.detune).coerceIn(0f, 1f),
            drone = p.optBoolean("drone", d.drone),
            space = f(p, "space", d.space).coerceIn(0f, 1f),
            shimmer = f(p, "shimmer", d.shimmer).coerceIn(0f, 1f),
            tone = f(p, "tone", d.tone).coerceIn(0f, 1f),
            scan = scan.coerceIn(-1f, 1f),
        )
        val seedText = o.optString("masterSeed", "").removePrefix("0x")
        val source = o.optJSONObject("source")
        Shared(
            name = o.optString("name", "Imported Seed").take(60).ifBlank { "Imported Seed" },
            masterSeed = java.lang.Long.parseUnsignedLong(seedText, 16),
            grain = grain,
            sourceName = source?.optString("name", "") ?: "",
            sourceHash = source?.optString("hash", "") ?: "",
        )
    }.getOrNull()
}
