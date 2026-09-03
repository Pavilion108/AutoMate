package com.automate.data.repository

import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import com.automate.data.db.entity.AccountEntity
import com.automate.data.db.entity.GeofenceLocationEntity
import com.automate.data.db.entity.TaskEntity
import com.automate.data.db.entity.VariableEntity
import com.automate.domain.model.Account
import com.automate.domain.model.GeofenceLocation
import com.automate.domain.model.Task
import com.automate.domain.model.Variable
import com.automate.domain.repository.TaskRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val accountDao: AccountDao,
    private val geofenceLocationDao: GeofenceLocationDao,
    private val variableDao: VariableDao
) : TaskRepository {

    private val gson = Gson()

    // Tasks
    override fun getAllTasks(): Flow<List<Task>> =
        taskDao.getAllTasks().map { entities -> entities.map { it.toDomain() } }

    override fun getEnabledTasks(): Flow<List<Task>> =
        taskDao.getEnabledTasks().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getTaskById(id: Long): Task? =
        taskDao.getTaskById(id)?.toDomain()

    override suspend fun insertTask(task: Task): Long =
        taskDao.insertTask(task.toEntity())

    override suspend fun updateTask(task: Task) =
        taskDao.updateTask(task.toEntity())

    override suspend fun deleteTask(task: Task) =
        taskDao.deleteTask(task.toEntity())

    override suspend fun updateLastRun(id: Long, result: String) =
        taskDao.updateLastRun(id, System.currentTimeMillis(), result)

    // Accounts
    override fun getAllAccounts(): Flow<List<Account>> =
        accountDao.getAllAccounts().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getAccountById(id: Long): Account? =
        accountDao.getAccountById(id)?.toDomain()

    override suspend fun insertAccount(account: Account): Long =
        accountDao.insertAccount(account.toEntity())

    override suspend fun updateAccount(account: Account) =
        accountDao.updateAccount(account.toEntity())

    override suspend fun deleteAccount(account: Account) =
        accountDao.deleteAccount(account.toEntity())

    override suspend fun setActiveAccount(id: Long, profileType: String) {
        accountDao.deactivateAllAccounts(profileType)
        accountDao.setActiveAccount(id)
    }

    // Locations
    override fun getAllLocations(): Flow<List<GeofenceLocation>> =
        geofenceLocationDao.getAllLocations().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getLocationById(id: Long): GeofenceLocation? =
        geofenceLocationDao.getLocationById(id)?.toDomain()

    override suspend fun insertLocation(location: GeofenceLocation): Long =
        geofenceLocationDao.insertLocation(location.toEntity())

    override suspend fun updateLocation(location: GeofenceLocation) =
        geofenceLocationDao.updateLocation(location.toEntity())

    override suspend fun deleteLocation(location: GeofenceLocation) =
        geofenceLocationDao.deleteLocation(location.toEntity())

    // Variables
    override suspend fun getVariable(name: String): Variable? =
        variableDao.getVariable(name)?.let {
            Variable(it.name, it.value, com.automate.domain.model.VariableType.valueOf(it.type))
        }

    override suspend fun setVariable(name: String, value: String, type: String) =
        variableDao.setVariable(VariableEntity(name, value, type))

    override suspend fun clearVariables() =
        variableDao.clearAll()

    // Mappers
    private fun TaskEntity.toDomain(): Task {
        val triggerType = com.automate.domain.model.TriggerType.valueOf(
            gson.fromJson(triggerJson, Map::class.java)["type"] as String
        )
        return Task(
            id = id,
            name = name,
            isEnabled = isEnabled,
            accountId = accountId,
            trigger = gson.fromJson(triggerJson, com.automate.domain.model.Trigger::class.java),
            constraints = gson.fromJson(
                constraintsJson,
                object : TypeToken<List<com.automate.domain.model.Constraint>>() {}.type
            ),
            actions = gson.fromJson(
                actionsJson,
                object : TypeToken<List<com.automate.domain.model.Action>>() {}.type
            ),
            createdAt = createdAt,
            lastRunAt = lastRunAt,
            lastRunResult = lastRunResult
        )
    }

    private fun Task.toEntity() = TaskEntity(
        id = id,
        name = name,
        isEnabled = isEnabled,
        accountId = accountId,
        triggerJson = gson.toJson(trigger),
        constraintsJson = gson.toJson(constraints),
        actionsJson = gson.toJson(actions),
        createdAt = createdAt,
        lastRunAt = lastRunAt,
        lastRunResult = lastRunResult
    )

    private fun AccountEntity.toDomain() = Account(
        id = id,
        profileType = profileType,
        displayName = displayName,
        isActive = isActive,
        credentialBlob = credentialBlob,
        settings = gson.fromJson(
            settingsJson,
            object : TypeToken<Map<String, String>>() {}.type
        ) ?: emptyMap(),
        createdAt = createdAt
    )

    private fun Account.toEntity() = AccountEntity(
        id = id,
        profileType = profileType,
        displayName = displayName,
        isActive = isActive,
        credentialBlob = credentialBlob,
        settingsJson = gson.toJson(settings),
        createdAt = createdAt
    )

    private fun GeofenceLocationEntity.toDomain() = GeofenceLocation(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters
    )

    private fun GeofenceLocation.toEntity() = GeofenceLocationEntity(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters
    )
}
