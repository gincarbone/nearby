package com.nearby.domain.repository

import com.nearby.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getLocalUser(): Flow<User?>
    suspend fun getLocalUserSync(): User?
    suspend fun createUser(displayName: String): User
    suspend fun updateDisplayName(displayName: String)
    suspend fun isOnboardingComplete(): Boolean
    suspend fun setOnboardingComplete()
}
