package com.automate.core.di

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.automate.data.db.AutoMateDatabase
import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import com.automate.domain.model.ActionType
import com.automate.domain.model.TriggerType
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val gson = Gson()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutoMateDatabase {
        return Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            "automate.db"
        ).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)

                // Seed geofence
                db.execSQL("""
                    INSERT INTO geofence_locations (name, latitude, longitude, radiusMeters)
                    VALUES ('DKC Office', 19.126812, 72.838510, 200.0)
                """.trimIndent())

                // Seed variables
                val variables = listOf(
                    Triple("armed", "false", "BOOLEAN"),
                    Triple("timed_in_today", "false", "BOOLEAN"),
                    Triple("exit_watch", "false", "BOOLEAN"),
                    Triple("going_to_work", "false", "BOOLEAN"),
                    Triple("work_duration_hours", "8.5", "INTEGER"),
                    Triple("exit_watch_distance", "50", "INTEGER")
                )
                for ((name, value, type) in variables) {
                    val cv = ContentValues().apply {
                        put("name", name)
                        put("value", value)
                        put("type", type)
                    }
                    db.insert("variables", androidx.sqlite.db.ConflictStrategy.REPLACE, cv)
                }

                // Seed Beehive Time-In task
                val timeInTrigger = gson.toJson(mapOf(
                    "type" to TriggerType.GEOFENCE_ENTER.name,
                    "latitude" to 19.126812,
                    "longitude" to 72.838510,
                    "radiusMeters" to 200f
                ))
                val timeInConstraints = gson.toJson(listOf(
                    mapOf("variableName" to "armed", "operator" to "EQUALS", "value" to "true"),
                    mapOf("variableName" to "timed_in_today", "operator" to "EQUALS", "value" to "false")
                ))
                val timeInActions = gson.toJson(listOf(
                    mapOf("type" to ActionType.LAUNCH_APP.name, "packageName" to "com.app.beehivehrms"),
                    mapOf("type" to ActionType.WAIT.name, "seconds" to 3),
                    mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "SIGN IN"),
                    mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                    mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "TIME IN"),
                    mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                    mapOf("type" to ActionType.POPUP_HANDLER.name),
                    mapOf("type" to ActionType.GLOBAL_ACTION.name, "globalActionType" to "home")
                ))
                val timeInCv = ContentValues().apply {
                    put("name", "Beehive Time-In")
                    put("isEnabled", 1)
                    put("accountId", 0)
                    put("triggerJson", timeInTrigger)
                    put("constraintsJson", timeInConstraints)
                    put("actionsJson", timeInActions)
                    put("createdAt", System.currentTimeMillis())
                }
                db.insert("tasks", androidx.sqlite.db.ConflictStrategy.REPLACE, timeInCv)

                // Seed Beehive Time-Out task
                val timeOutTrigger = gson.toJson(mapOf(
                    "type" to TriggerType.GEOFENCE_EXIT.name,
                    "latitude" to 19.126812,
                    "longitude" to 72.838510,
                    "radiusMeters" to 150f
                ))
                val timeOutConstraints = gson.toJson(listOf(
                    mapOf("variableName" to "exit_watch", "operator" to "EQUALS", "value" to "true"),
                    mapOf("variableName" to "timed_in_today", "operator" to "EQUALS", "value" to "true")
                ))
                val timeOutActions = gson.toJson(listOf(
                    mapOf("type" to ActionType.LAUNCH_APP.name, "packageName" to "com.app.beehivehrms"),
                    mapOf("type" to ActionType.WAIT.name, "seconds" to 3),
                    mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "TIME OUT"),
                    mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                    mapOf("type" to ActionType.POPUP_HANDLER.name),
                    mapOf("type" to ActionType.GLOBAL_ACTION.name, "globalActionType" to "home")
                ))
                val timeOutCv = ContentValues().apply {
                    put("name", "Beehive Time-Out")
                    put("isEnabled", 1)
                    put("accountId", 0)
                    put("triggerJson", timeOutTrigger)
                    put("constraintsJson", timeOutConstraints)
                    put("actionsJson", timeOutActions)
                    put("createdAt", System.currentTimeMillis())
                }
                db.insert("tasks", androidx.sqlite.db.ConflictStrategy.REPLACE, timeOutCv)
            }
        }).build()
    }

    @Provides
    fun provideTaskDao(db: AutoMateDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideAccountDao(db: AutoMateDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideGeofenceLocationDao(db: AutoMateDatabase): GeofenceLocationDao = db.geofenceLocationDao()

    @Provides
    fun provideVariableDao(db: AutoMateDatabase): VariableDao = db.variableDao()
}
