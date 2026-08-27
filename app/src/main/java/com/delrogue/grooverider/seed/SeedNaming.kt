package com.delrogue.grooverider.seed

/**
 * Deterministic auto-naming (spec 3.5): the same masterSeed always produces
 * the same name, so names are stable and memorable rather than arbitrary --
 * "Bitter Amber Drift", "Slow Tin Halo", "Ninth Ash Bloom".
 */
object SeedNaming {

    private val adjectives = listOf(
        "Bitter", "Slow", "Ninth", "Hollow", "Quiet", "Faded", "Pale", "Restless",
        "Distant", "Cracked", "Velvet", "Brittle", "Amber", "Frozen", "Burnt", "Thin",
        "Molten", "Dusty", "Shallow", "Wandering", "Silent", "Rusted", "Drifting", "Hazy",
        "Waning", "Sunken", "Feral", "Ghostly", "Cold", "Warm", "Muted", "Wild",
    )

    private val nouns = listOf(
        "Amber", "Drift", "Halo", "Bloom", "Ash", "Tin", "Ember", "Fog",
        "Copper", "Static", "Dust", "Rain", "Slate", "Frost", "Bone", "Salt",
        "Iron", "Glass", "Smoke", "Tide", "Moss", "Clay", "Wire", "Shale",
        "Quartz", "Mica", "Lichen", "Vapor", "Cinder", "Rust", "Chalk", "Dew",
        "Pollen", "Gravel", "Marrow", "Wax", "Tar", "Silt", "Loam", "Husk",
        "Signal", "Current", "Echo", "Static", "Nectar", "Resin", "Grain", "Veil",
    )

    fun nameFor(masterSeed: Long): String {
        val hash = Hash64.splitmix64(masterSeed)
        val adjective = adjectives[((hash and 0x7FFFFFFF) % adjectives.size).toInt()]
        val noun = nouns[(((hash ushr 8) and 0x7FFFFFFF) % nouns.size).toInt()]
        return "$adjective $noun"
    }
}
