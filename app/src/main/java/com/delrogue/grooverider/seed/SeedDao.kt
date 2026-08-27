package com.delrogue.grooverider.seed

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SeedDao {
    @Query("SELECT * FROM seeds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Seed>>

    @Query("SELECT * FROM seeds WHERE id = :id")
    suspend fun getById(id: String): Seed?

    @Query("SELECT * FROM seeds WHERE parentId = :parentId ORDER BY createdAt ASC")
    suspend fun getChildren(parentId: String): List<Seed>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(seed: Seed)

    @Update
    suspend fun update(seed: Seed)

    @Delete
    suspend fun delete(seed: Seed)

    @Query("DELETE FROM seeds WHERE id = :id")
    suspend fun deleteById(id: String)
}
