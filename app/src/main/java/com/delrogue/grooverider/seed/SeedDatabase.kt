package com.delrogue.grooverider.seed

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.delrogue.grooverider.render.Render
import com.delrogue.grooverider.render.RenderDao

/**
 * Room owns the Seed library and the renders index (spec 7). Sources are
 * content-addressed files with their own flat index (see
 * com.delrogue.grooverider.source.SourceStore) -- Seeds and renders reference
 * them by hash string, so the two stores never need to share a schema.
 */
@Database(entities = [Seed::class, Render::class], version = 1, exportSchema = false)
abstract class SeedDatabase : RoomDatabase() {
    abstract fun seedDao(): SeedDao
    abstract fun renderDao(): RenderDao

    companion object {
        @Volatile private var instance: SeedDatabase? = null

        fun get(context: Context): SeedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SeedDatabase::class.java,
                "grooverider_seeds.db",
            ).build().also { instance = it }
        }
    }
}
