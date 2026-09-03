package com.automate.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileType: String,
    val displayName: String,
    val isActive: Boolean = false,
    val credentialBlob: ByteArray? = null,
    val settingsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
