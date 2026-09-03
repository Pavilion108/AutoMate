package com.automate.core.di

import android.content.Context
import com.automate.data.db.dao.AccountDao
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.dao.TaskDao
import com.automate.data.db.dao.VariableDao
import com.automate.data.repository.TaskRepositoryImpl
import com.automate.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTaskRepository(
        taskDao: TaskDao,
        accountDao: AccountDao,
        geofenceLocationDao: GeofenceLocationDao,
        variableDao: VariableDao
    ): TaskRepository {
        return TaskRepositoryImpl(taskDao, accountDao, geofenceLocationDao, variableDao)
    }
}
