package com.automate.data.db.dao

import androidx.room.*
import com.automate.data.db.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY displayName")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE profileType = :type ORDER BY displayName")
    fun getAccountsByType(type: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Insert
    suspend fun insertAccount(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0 WHERE profileType = :type")
    suspend fun deactivateAllAccounts(type: String)

    @Query("UPDATE accounts SET isActive = 1 WHERE id = :id")
    suspend fun setActiveAccount(id: Long)
}
