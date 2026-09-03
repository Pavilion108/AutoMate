package com.automate.data.db.dao

import androidx.room.*
import com.automate.data.db.entity.GeofenceLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceLocationDao {
    @Query("SELECT * FROM geofence_locations ORDER BY name")
    fun getAllLocations(): Flow<List<GeofenceLocationEntity>>

    @Query("SELECT * FROM geofence_locations WHERE id = :id")
    suspend fun getLocationById(id: Long): GeofenceLocationEntity?

    @Insert
    suspend fun insertLocation(location: GeofenceLocationEntity): Long

    @Update
    suspend fun updateLocation(location: GeofenceLocationEntity)

    @Delete
    suspend fun deleteLocation(location: GeofenceLocationEntity)
}
