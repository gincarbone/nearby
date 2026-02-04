package com.nearby.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.Constants
import com.nearby.core.util.generateUUID
import com.nearby.data.local.dao.UserDao
import com.nearby.data.local.entity.UserEntity
import com.nearby.data.nearby.PreferencesManager
import com.nearby.domain.model.User
import com.nearby.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    private val dataStore: DataStore<Preferences>,
    private val preferencesManager: PreferencesManager
) : UserRepository {

    private val onboardingCompleteKey = booleanPreferencesKey(Constants.KEY_ONBOARDING_COMPLETE)

    override fun getLocalUser(): Flow<User?> {
        return userDao.getLocalUser().map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun getLocalUserSync(): User? {
        return userDao.getLocalUserSync()?.toDomain()
    }

    override suspend fun createUser(displayName: String): User {
        val keyPair = cryptoManager.generateKeyPair()
        val userId = generateUUID()

        val entity = UserEntity(
            id = userId,
            displayName = displayName,
            publicKey = keyPair.public.encoded,
            privateKeyEncrypted = keyPair.private.encoded,
            createdAt = System.currentTimeMillis()
        )

        userDao.insertUser(entity)
        preferencesManager.setUserDisplayName(displayName)
        return entity.toDomain()
    }

    override suspend fun updateDisplayName(displayName: String) {
        val user = userDao.getLocalUserSync() ?: return
        userDao.updateDisplayName(user.id, displayName)
        preferencesManager.setUserDisplayName(displayName)
    }

    override suspend fun isOnboardingComplete(): Boolean {
        return dataStore.data.first()[onboardingCompleteKey] ?: false
    }

    override suspend fun setOnboardingComplete() {
        dataStore.edit { preferences ->
            preferences[onboardingCompleteKey] = true
        }
    }

    private fun UserEntity.toDomain(): User {
        return User(
            id = id,
            displayName = displayName,
            publicKey = cryptoManager.decodePublicKey(publicKey),
            createdAt = createdAt
        )
    }
}
