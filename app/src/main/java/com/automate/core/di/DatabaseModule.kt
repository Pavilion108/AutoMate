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
import com.automate.data.db.entity.VariableEntity
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

                    database.geofenceLocationDao().insertLocation(
                        GeofenceLocationEntity(
                            name = "DKC Office",
                            latitude = 19.126812,
                            longitude = 72.838510,
                            radiusMeters = 200f
                        )
                    )

                    database.variableDao().setVariable(VariableEntity("armed", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("timed_in_today", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("exit_watch", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("going_to_work", "false", "BOOLEAN"))
                    database.variableDao().setVariable(VariableEntity("work_duration_hours", "9", "INTEGER"))

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
