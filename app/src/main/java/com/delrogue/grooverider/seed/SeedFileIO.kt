package com.delrogue.grooverider.seed

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Seed export/import as `.grvr` JSON for backup (spec M7) -- a full Seed
 * record, byte arrays base64-encoded, independent of any WAV. */
object SeedFileIO {

    fun export(seed: Seed): String = JSONObject().apply {
        put("id", seed.id)
        put("name", seed.name)
        put("masterSeed", seed.masterSeed)
        put("params", Base64.encodeToString(seed.params, Base64.NO_WRAP))
        put("modRoutes", Base64.encodeToString(seed.modRoutes, Base64.NO_WRAP))
        put("sourceHash", seed.sourceHash)
        put("inPointMs", seed.inPointMs)
        put("outPointMs", seed.outPointMs)
        put("parentId", seed.parentId ?: JSONObject.NULL)
        put("mutationDistance", seed.mutationDistance)
        put("lockedParams", seed.lockedParams)
        put("favourite", seed.favourite)
        put("renderCount", seed.renderCount)
        put("createdAt", seed.createdAt)
        put("waveformThumb", Base64.encodeToString(seed.waveformThumb, Base64.NO_WRAP))
    }.toString(2)

    fun exportToFile(seed: Seed, file: File) = file.writeText(export(seed))

    /** Always a fresh id and no parent -- an imported Seed starts a new lineage. */
    fun import(json: String): Seed? = runCatching {
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

    fun importFromFile(file: File): Seed? = runCatching { file.readText() }.getOrNull()?.let { import(it) }
}
