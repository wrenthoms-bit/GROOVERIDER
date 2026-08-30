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
    // Default data class equals would compare the ByteArray fields by
    // reference, so two Seeds reloaded from the DB with identical content
    // would never compare equal. But comparing by id alone is just as
    // broken the other way: StateFlow conflates a value assignment that
    // .equals() the previous one, so id-only equality silently drops
    // in-place edits (lock/favourite toggles) that keep the same id --
    // the write reaches the DB but the UI never recomposes to show it.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Seed) return false
        return id == other.id &&
            name == other.name &&
            masterSeed == other.masterSeed &&
            params.contentEquals(other.params) &&
            modRoutes.contentEquals(other.modRoutes) &&
            sourceHash == other.sourceHash &&
            inPointMs == other.inPointMs &&
            outPointMs == other.outPointMs &&
            parentId == other.parentId &&
            mutationDistance == other.mutationDistance &&
            lockedParams == other.lockedParams &&
            favourite == other.favourite &&
            renderCount == other.renderCount &&
            createdAt == other.createdAt &&
            waveformThumb.contentEquals(other.waveformThumb)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + masterSeed.hashCode()
        result = 31 * result + params.contentHashCode()
        result = 31 * result + modRoutes.contentHashCode()
        result = 31 * result + sourceHash.hashCode()
        result = 31 * result + inPointMs
        result = 31 * result + outPointMs
        result = 31 * result + (parentId?.hashCode() ?: 0)
        result = 31 * result + mutationDistance.hashCode()
        result = 31 * result + lockedParams.hashCode()
        result = 31 * result + favourite.hashCode()
        result = 31 * result + renderCount
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + waveformThumb.contentHashCode()
        return result
    }
}
