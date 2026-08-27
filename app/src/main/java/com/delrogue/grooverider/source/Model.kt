package com.delrogue.grooverider.source

/** Decoded audio in memory: interleaved float32 at [sampleRate]. */
data class DecodedAudio(
    val samples: FloatArray,   // interleaved
    val channels: Int,
    val sampleRate: Int,
) {
    val frames: Long get() = if (channels == 0) 0 else samples.size.toLong() / channels
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000 / sampleRate
}

/** One entry in the content-addressed source index. */
data class SourceRecord(
    val hash: String,          // SHA-256 of the decoded PCM (the content address)
    val name: String,          // original file name or "Mic take …"
    val channels: Int,
    val sampleRate: Int,
    val frames: Long,
    val importedAt: Long,
) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000 / sampleRate
}
