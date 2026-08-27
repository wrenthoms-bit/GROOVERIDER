package com.delrogue.grooverider.seed

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved recipe (spec 3.3) -- the product. [params] is a packed [ParamState]
 * snapshot; [sourceHash] references the content-addressed source store by
 * hash, never by path, so a Seed can be re-pointed at any source (spec 3.4).
 */
@Entity(tableName = "seeds")
data class Seed(
    @PrimaryKey val id: String,             // UUID
    val name: String,                       // auto-generated, user-renameable
    val masterSeed: Long,                   // the whole random universe (spec 3.2)
    val params: ByteArray,                  // packed ParamState snapshot
    val modRoutes: ByteArray,               // packed mod matrix, wired up in M4
    val sourceHash: String,
    val inPointMs: Int,
    val outPointMs: Int,
    val parentId: String?,                  // lineage (M7)
    val mutationDistance: Float,            // how far from parent (M7)
    val lockedParams: Long,                 // bitmask, wired up in M7
    val favourite: Boolean,
    val renderCount: Int,
    val createdAt: Long,
    val waveformThumb: ByteArray,           // 256-point peak envelope for the library grid
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Seed) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
