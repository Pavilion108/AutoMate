package com.automate.domain.model

data class Account(
    val id: Long = 0,
    val profileType: String,
    val displayName: String,
    val isActive: Boolean = false,
    val credentialBlob: ByteArray? = null,
    val settings: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Account) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class GeofenceLocation(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f
)

data class Variable(
    val name: String,
    val value: String,
    val type: VariableType = VariableType.BOOLEAN
)

enum class VariableType {
    BOOLEAN,
    STRING,
    INTEGER,
    TIME
}

enum class ProfileType(val displayName: String) {
    BEEHIVE("Beehive HRMS"),
    WHATSAPP("WhatsApp"),
    METRO("Metro Tickets"),
    CUSTOM("Custom App")
}
