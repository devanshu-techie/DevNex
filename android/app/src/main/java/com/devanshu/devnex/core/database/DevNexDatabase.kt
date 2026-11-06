package com.devanshu.devnex.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.devanshu.devnex.core.security.KeystoreManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [WorkspaceEntity::class, TaskEntity::class, ChecklistEntity::class, ChannelEntity::class,
        MessageEntity::class, NotificationEntity::class, OutboxEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DevNexDatabase : RoomDatabase() {
    abstract fun workspaces(): WorkspaceDao
    abstract fun tasks(): TaskDao
    abstract fun checklist(): ChecklistDao
    abstract fun chat(): ChatDao
    abstract fun notifications(): NotificationDao
    abstract fun outbox(): OutboxDao
    abstract fun syncState(): SyncStateDao

    companion object {
        /** Room on top of SQLCipher. The passphrase is random and wrapped by an Android Keystore key. */
        fun build(context: Context, keystore: KeystoreManager): DevNexDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(keystore.databasePassphrase())
            return Room.databaseBuilder(context, DevNexDatabase::class.java, "devnex.db")
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()   // cache only; the server is the source of truth
                .build()
        }
    }
}
