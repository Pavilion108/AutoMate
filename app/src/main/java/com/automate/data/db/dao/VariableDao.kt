package com.automate.data.db.dao

import androidx.room.*
import com.automate.data.db.entity.VariableEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VariableDao {
    @Query("SELECT * FROM variables ORDER BY name")
    fun getAllVariables(): Flow<List<VariableEntity>>

    @Query("SELECT * FROM variables WHERE name = :name")
    suspend fun getVariable(name: String): VariableEntity?

    @Query("SELECT value FROM variables WHERE name = :name")
    suspend fun getVariableValue(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setVariable(variable: VariableEntity)

    @Query("DELETE FROM variables WHERE name = :name")
    suspend fun deleteVariable(name: String)

    @Query("DELETE FROM variables")
    suspend fun clearAll()
}
