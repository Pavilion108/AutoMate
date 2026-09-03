package com.automate.domain.repository

import com.automate.domain.model.Account
import com.automate.domain.model.GeofenceLocation
import com.automate.domain.model.Task
import com.automate.domain.model.Variable
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getAllTasks(): Flow<List<Task>>
    fun getEnabledTasks(): Flow<List<Task>>
    suspend fun getTaskById(id: Long): Task?
    suspend fun insertTask(task: Task): Long
    suspend fun updateTask(task: Task)
    suspend fun deleteTask(task: Task)
    suspend fun updateLastRun(id: Long, result: String)

    fun getAllAccounts(): Flow<List<Account>>
    suspend fun getAccountById(id: Long): Account?
    suspend fun insertAccount(account: Account): Long
    suspend fun updateAccount(account: Account)
    suspend fun deleteAccount(account: Account)
    suspend fun setActiveAccount(id: Long, profileType: String)

    fun getAllLocations(): Flow<List<GeofenceLocation>>
    suspend fun getLocationById(id: Long): GeofenceLocation?
    suspend fun insertLocation(location: GeofenceLocation): Long
    suspend fun updateLocation(location: GeofenceLocation)
    suspend fun deleteLocation(location: GeofenceLocation)

    suspend fun getVariable(name: String): Variable?
    suspend fun setVariable(name: String, value: String, type: String = "BOOLEAN")
    suspend fun clearVariables()
}
