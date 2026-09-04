package com.automate.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.automate.data.db.AutoMateDatabase
import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import com.automate.data.db.entity.GeofenceLocationEntity
import com.automate.data.db.entity.TaskEntity
import com.automate.data.db.entity.VariableEntity
import com.automate.domain.model.ActionType
import com.automate.domain.model.TriggerType
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                scope.launch {
                    val database = Room.databaseBuilder(
                        context,
                        AutoMateDatabase::class.java,
                        "automate.db"
                    ).build()

                    // Seed geofence
                    database.geofenceLocationDao().insertLocation(
                        GeofenceLocationEntity(
                            name = "DKC Office",
                            latitude = 19.126812,
                            longitude = 72.838510,
                            radiusMeters = 200f
                        )
                    )

                    // Seed variables
                    database.variableDao().setVariable(VariableEntity("armed", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("timed_in_today", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("exit_watch", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("going_to_work", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("work_duration_hours", "9", "INTEGER"))

                    // Seed Beehive Time-In task
                    database.taskDao().insertTask(TaskEntity(
                        name = "Beehive Time-In",
                        isEnabled = true,
                        triggerJson = gson.toJson(mapOf(
                            "type" to TriggerType.GEOFENCE_ENTER.name,
                            "latitude" to 19.126812,
                            "longitude" to 72.838510,
                            "radiusMeters" to 200f
                        )),
                        constraintsJson = gson.toJson(listOf(
                            mapOf("variableName" to "armed", "operator" to "EQUALS", "value" to "true"),
                            mapOf("variableName" to "timed_in_today", "operator" to "EQUALS", "value" to "false")
                        )),
                        actionsJson = gson.toJson(listOf(
                            mapOf("type" to ActionType.LAUNCH_APP.name, "packageName" to "com.app.beehivehrms"),
                            mapOf("type" to ActionType.WAIT.name, "seconds" to 3),
                            mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "SIGN IN"),
                            mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                            mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "TIME IN"),
                            mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                            mapOf("type" to ActionType.POPUP_HANDLER.name),
                            mapOf("type" to ActionType.GLOBAL_ACTION.name, "globalActionType" to "home")
                        ))
                    ))

                    // Seed Beehive Time-Out task
                    database.taskDao().insertTask(TaskEntity(
                        name = "Beehive Time-Out",
                        isEnabled = true,
                        triggerJson = gson.toJson(mapOf(
                            "type" to TriggerType.GEOFENCE_EXIT.name,
                            "latitude" to 19.126812,
                            "longitude" to 72.838510,
                            "radiusMeters" to 150f
                        )),
                        constraintsJson = gson.toJson(listOf(
                            mapOf("variableName" to "exit_watch", "operator" to "EQUALS", "value" to "true"),
                            mapOf("variableName" to "timed_in_today", "operator" to "EQUALS", "value" to "true")
                        )),
                        actionsJson = gson.toJson(listOf(
                            mapOf("type" to ActionType.LAUNCH_APP.name, "packageName" to "com.app.beehivehrms"),
                            mapOf("type" to ActionType.WAIT.name, "seconds" to 3),
                            mapOf("type" to ActionType.CLICK_ELEMENT.name, "target" to "TIME OUT"),
                            mapOf("type" to ActionType.WAIT.name, "seconds" to 2),
                            mapOf("type" to ActionType.POPUP_HANDLER.name),
                            mapOf("type" to ActionType.GLOBAL_ACTION.name, "globalActionType" to "home")
                        ))
                    ))

                    database.close()
                }
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
