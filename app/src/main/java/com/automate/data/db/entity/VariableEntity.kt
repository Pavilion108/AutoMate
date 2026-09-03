package com.automate.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "variables")
data class VariableEntity(
    @PrimaryKey val name: String,
    val value: String,
    val type: String = "BOOLEAN"
)
