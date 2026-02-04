package com.nearby.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nearby.data.local.dao.ConversationDao
import com.nearby.data.local.dao.MessageDao
import com.nearby.data.local.dao.PeerDao
import com.nearby.data.local.dao.StoredMessageDao
import com.nearby.data.local.dao.UserDao
import com.nearby.data.local.db.NearByDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS stored_messages (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    originalSender TEXT NOT NULL,
                    finalDestination TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    originalTimestamp INTEGER NOT NULL,
                    storedAt INTEGER NOT NULL,
                    expiresAt INTEGER NOT NULL,
                    deliveryAttempts INTEGER NOT NULL DEFAULT 0,
                    lastDeliveryAttempt INTEGER,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX IF NOT EXISTS index_stored_messages_finalDestination ON stored_messages(finalDestination)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_stored_messages_expiresAt ON stored_messages(expiresAt)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): NearByDatabase {
        return Room.databaseBuilder(
            context,
            NearByDatabase::class.java,
            NearByDatabase.DATABASE_NAME
        )
        .addMigrations(MIGRATION_1_2, NearByDatabase.MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideUserDao(database: NearByDatabase): UserDao {
        return database.userDao()
    }

    @Provides
    @Singleton
    fun providePeerDao(database: NearByDatabase): PeerDao {
        return database.peerDao()
    }

    @Provides
    @Singleton
    fun provideConversationDao(database: NearByDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: NearByDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideStoredMessageDao(database: NearByDatabase): StoredMessageDao {
        return database.storedMessageDao()
    }
}
