package com.automate.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val accountId: Long = 0,
    val triggerJson: String,
    val constraintsJson: String = "[]",
    val actionsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val lastRunResult: String? = null
)
