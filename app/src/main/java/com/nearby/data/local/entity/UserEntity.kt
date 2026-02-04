package com.nearby.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    val publicKey: ByteArray,
    val privateKeyEncrypted: ByteArray,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UserEntity

        if (id != other.id) return false
        if (displayName != other.displayName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!privateKeyEncrypted.contentEquals(other.privateKeyEncrypted)) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + privateKeyEncrypted.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
