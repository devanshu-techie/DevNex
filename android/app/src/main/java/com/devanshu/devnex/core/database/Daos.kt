package com.devanshu.devnex.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY name") suspend fun all(): List<WorkspaceEntity>
    @Upsert suspend fun upsert(items: List<WorkspaceEntity>)
    @Query("DELETE FROM workspaces") suspend fun clear()
    @Transaction suspend fun replace(items: List<WorkspaceEntity>) { clear(); upsert(items) }
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE companyId = :companyId ORDER BY CASE priority WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END, COALESCE(dueAt, 9223372036854775807)")
    fun observe(companyId: String): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id = :id") fun observeOne(id: String): Flow<TaskEntity?>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): TaskEntity?
    @Query("SELECT MAX(updatedAt) FROM tasks WHERE companyId = :companyId") suspend fun latest(companyId: String): Long?
    @Upsert suspend fun upsert(items: List<TaskEntity>)
    @Query("DELETE FROM tasks WHERE companyId = :companyId") suspend fun clearCompany(companyId: String)
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist WHERE taskId = :taskId ORDER BY position") fun observe(taskId: String): Flow<List<ChecklistEntity>>
    @Upsert suspend fun upsert(items: List<ChecklistEntity>)
    @Query("UPDATE checklist SET done = :done WHERE id = :id") suspend fun setDone(id: String, done: Boolean)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM channels WHERE companyId = :companyId ORDER BY COALESCE(lastAt, 0) DESC") fun channels(companyId: String): Flow<List<ChannelEntity>>
    @Upsert suspend fun upsertChannels(items: List<ChannelEntity>)
    @Query("SELECT * FROM messages WHERE channelId = :channelId ORDER BY at DESC LIMIT 300") fun messages(channelId: String): Flow<List<MessageEntity>>
    @Upsert suspend fun upsertMessages(items: List<MessageEntity>)
    @Query("UPDATE messages SET pending = 0 WHERE id = :id") suspend fun markSent(id: String)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE companyId = :companyId ORDER BY at DESC LIMIT 100") fun observe(companyId: String): Flow<List<NotificationEntity>>
    @Upsert suspend fun upsert(items: List<NotificationEntity>)
    @Query("UPDATE notifications SET read = 1 WHERE id = :id") suspend fun markRead(id: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit") suspend fun next(limit: Int = 50): List<OutboxEntity>
    @Upsert suspend fun put(item: OutboxEntity)
    @Query("DELETE FROM outbox WHERE seq = :seq") suspend fun remove(seq: Long)
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq = :seq") suspend fun failed(seq: Long)
    @Query("SELECT COUNT(*) FROM outbox") fun pendingCount(): Flow<Int>
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key") suspend fun get(key: String): Long?
    @Upsert suspend fun put(item: SyncStateEntity)
}
