package com.automate.core.di

import android.content.Context
import androidx.room.Room
import com.automate.data.db.AutoMateDatabase
import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutoMateDatabase {
        return Room.databaseBuilder(
            context,
            AutoMateDatabase::class.java,
            "automate.db"
        ).build()
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
