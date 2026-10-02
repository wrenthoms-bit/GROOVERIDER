package com.delrogue.grooverider.seed

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Seed files (`.grvr`). They are written in the format shared with the web app
 * ([SeedShareFormat]). Files written by earlier versions of this app -- a full
 * database record with its byte arrays base64-encoded -- still import.
 */
object SeedFileIO {

    fun export(seed: Seed, sourceName: String): String = SeedShareFormat.encode(
        SeedShareFormat.Shared(
            name = seed.name, masterSeed = seed.masterSeed, grain = ParamState.unpack(seed.params),
            sourceName = sourceName, sourceHash = seed.sourceHash,
        )
    )

    fun exportToFile(seed: Seed, sourceName: String, file: File) = file.writeText(export(seed, sourceName))

    /**
     * Always a fresh id and no parent -- an imported Seed starts a new lineage.
     * A shared-format file names its sound rather than carrying it, so
     * [resolveSource] is asked which source here to put it on (null = none
     * suitable, and the import fails); it returns that source's hash and the
     * thumbnail to show.
     */
    fun import(json: String, resolveSource: (name: String, hash: String) -> Pair<String, ByteArray>?): Seed? {
        val shared = SeedShareFormat.decode(json) ?: return importLegacy(json)
        val (sourceHash, thumb) = resolveSource(shared.sourceName, shared.sourceHash) ?: return null
        return Seed(
            id = UUID.randomUUID().toString(),
            name = shared.name,
            masterSeed = shared.masterSeed,
            params = ParamState.pack(shared.grain),
            modRoutes = ByteArray(0),
            sourceHash = sourceHash,
            inPointMs = 0, outPointMs = 0,
            parentId = null, mutationDistance = 0f, lockedParams = 0L,
            favourite = false, renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = thumb,
        )
    }

    private fun importLegacy(json: String): Seed? = runCatching {
        val o = JSONObject(json)
        Seed(
            id = UUID.randomUUID().toString(),
            name = o.optString("name", "Imported Seed"),
            masterSeed = o.getLong("masterSeed"),
            params = Base64.decode(o.getString("params"), Base64.NO_WRAP),
            modRoutes = if (o.has("modRoutes")) Base64.decode(o.getString("modRoutes"), Base64.NO_WRAP) else ByteArray(0),
            sourceHash = o.optString("sourceHash", ""),
            inPointMs = o.optInt("inPointMs", 0),
            outPointMs = o.optInt("outPointMs", 0),
            parentId = null,
            mutationDistance = o.optDouble("mutationDistance", 0.0).toFloat(),
            lockedParams = o.optLong("lockedParams", 0L),
            favourite = false,
            renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = if (o.has("waveformThumb")) Base64.decode(o.getString("waveformThumb"), Base64.NO_WRAP) else ByteArray(0),
        )
    }.getOrNull()

}
