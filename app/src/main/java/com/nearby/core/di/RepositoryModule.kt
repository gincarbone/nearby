package com.nearby.core.di

import com.nearby.data.repository.ConversationRepositoryImpl
import com.nearby.data.repository.MessageRepositoryImpl
import com.nearby.data.repository.PeerRepositoryImpl
import com.nearby.data.repository.UserRepositoryImpl
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.MessageRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(
        userRepositoryImpl: UserRepositoryImpl
    ): UserRepository

    @Binds
    @Singleton
    abstract fun bindPeerRepository(
        peerRepositoryImpl: PeerRepositoryImpl
    ): PeerRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        conversationRepositoryImpl: ConversationRepositoryImpl
    ): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        messageRepositoryImpl: MessageRepositoryImpl
    ): MessageRepository
}
