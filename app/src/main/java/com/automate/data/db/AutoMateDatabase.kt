package com.automate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import com.automate.data.db.entity.AccountEntity
import com.automate.data.db.entity.GeofenceLocationEntity
import com.automate.data.db.entity.TaskEntity
import com.automate.data.db.entity.VariableEntity

@Database(
    entities = [
        TaskEntity::class,
        AccountEntity::class,
        GeofenceLocationEntity::class,
        VariableEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AutoMateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun accountDao(): AccountDao
    abstract fun geofenceLocationDao(): GeofenceLocationDao
    abstract fun variableDao(): VariableDao
}
