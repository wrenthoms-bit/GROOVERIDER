package com.delrogue.grooverider.render

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One exported WAV (spec 7): just enough to find the file and its Seed again. */
@Entity(tableName = "renders")
data class Render(
    @PrimaryKey val id: String,
    val path: String,
    val seedId: String,
    val createdAt: Long,
)

@Dao
interface RenderDao {
    @Query("SELECT * FROM renders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Render>>

    @Insert
    suspend fun insert(render: Render)

    @Query("DELETE FROM renders WHERE id = :id")
    suspend fun delete(id: String)
}
