package com.nearby.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nearby.data.local.dao.ConversationDao
import com.nearby.data.local.dao.MessageDao
import com.nearby.data.local.dao.PeerDao
import com.nearby.data.local.dao.StoredMessageDao
import com.nearby.data.local.dao.UserDao
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.local.entity.ConversationEntity
import com.nearby.data.local.entity.MessageEntity
import com.nearby.data.local.entity.MessageStatus
import com.nearby.data.local.entity.PeerEntity
import com.nearby.data.local.entity.StoredMessageEntity
import com.nearby.data.local.entity.StoredMessageStatus
import com.nearby.data.local.entity.UserEntity

class Converters {
    @TypeConverter
    fun fromConnectionState(state: ConnectionState): String = state.name

    @TypeConverter
    fun toConnectionState(value: String): ConnectionState = ConnectionState.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromStoredMessageStatus(status: StoredMessageStatus): String = status.name

    @TypeConverter
    fun toStoredMessageStatus(value: String): StoredMessageStatus = StoredMessageStatus.valueOf(value)
}

@Database(
    entities = [
        UserEntity::class,
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        StoredMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NearByDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun storedMessageDao(): StoredMessageDao

    companion object {
        const val DATABASE_NAME = "nearby_database"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE peers ADD COLUMN lastSyncedAt INTEGER DEFAULT NULL")
            }
        }
    }
}
