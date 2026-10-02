package com.delrogue.grooverider.onboarding

import android.content.Context
import com.delrogue.grooverider.AppPrefs
import com.delrogue.grooverider.seed.SeedRepository
import com.delrogue.grooverider.source.SourceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Puts the bundled factory content into the user's library: Big River as a
 * source, and the four presets as Seeds on it. Runs once per content version,
 * on a fresh install and on an upgrade alike, and never touches anything the
 * user made.
 */
object FactoryInstaller {
    private const val CONTENT_VERSION = 2   // 1 = the eight placeholder presets on the synthetic demo source
    private val lock = Mutex()

    suspend fun ensure(context: Context) = lock.withLock {
        val appContext = context.applicationContext
        if (AppPrefs.factoryContentVersion(appContext) >= CONTENT_VERSION) return@withLock

        val sources = SourceRepository(appContext)
        val bigRiver = sources.importAsset(FactoryContent.BIG_RIVER_ASSET, FactoryContent.BIG_RIVER_NAME)
        val peaks = sources.peaks(bigRiver.hash)
        val thumb = ByteArray(256) { i ->
            val p = peaks.getOrElse(i * peaks.size / 256) { 0f }
            (p.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }

        val seeds = SeedRepository(appContext)
        FactoryContent.seeds(bigRiver.hash, thumb).forEach { seeds.importSeed(it) }
        AppPrefs.setFactoryContentVersion(appContext, CONTENT_VERSION)
    }
}
