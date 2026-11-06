package com.devanshu.devnex.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.devanshu.devnex.domain.model.*

/** Every tenant-owned entity carries companyId, and every DAO query filters on it. */

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val companyId: String,
    val name: String, val slug: String, val industry: String, val brandColor: Long, val status: String,
    val roleKey: String, val roleName: String, val roleRank: Int, val departmentId: String?,
    val permissions: List<String>, val modules: List<String>,
)

@Entity(tableName = "tasks", indices = [Index("companyId", "status"), Index("companyId", "assignedTo"), Index("parentTaskId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val companyId: String, val parentTaskId: String?, val departmentId: String?,
    val title: String, val description: String, val status: String, val priority: String,
    val createdBy: String, val assignedTo: String?, val isOpenPool: Boolean, val eligibleSkill: String?,
    val dueAt: Long?, val estimatedEffort: Double?, val approvalChain: List<String>, val approvalStage: Int,
    val blockedReason: String?, val version: Int, val updatedAt: Long, val createdAt: Long,
)

@Entity(tableName = "checklist", indices = [Index("taskId")])
data class ChecklistEntity(
    @PrimaryKey val id: String, val companyId: String, val taskId: String,
    val label: String, val done: Boolean, val position: Int,
)

@Entity(tableName = "channels", indices = [Index("companyId")])
data class ChannelEntity(
    @PrimaryKey val id: String, val companyId: String, val name: String, val kind: String,
    val lastMessage: String?, val lastAt: Long?,
)

@Entity(tableName = "messages", indices = [Index("channelId", "at")])
data class MessageEntity(
    @PrimaryKey val id: String, val companyId: String, val channelId: String, val senderId: String,
    val senderName: String, val body: String, val at: Long, val pending: Boolean,
)

@Entity(tableName = "notifications", indices = [Index("companyId", "at")])
data class NotificationEntity(
    @PrimaryKey val id: String, val companyId: String, val kind: String, val title: String,
    val body: String, val taskId: String?, val read: Boolean, val at: Long,
)

/** Pending low-conflict writes (messages, checklist ticks, comments). Claims & approvals are online-only. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val kind: String, val companyId: String, val refId: String,
    val a: String = "", val b: String = "", val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val key: String, val value: Long)

class Converters {
    private val sep = "\u001F"
    @TypeConverter fun fromList(v: List<String>): String = v.joinToString(sep)
    @TypeConverter fun toList(v: String): List<String> = if (v.isEmpty()) emptyList() else v.split(sep)
}

// ---------------------------------------------------------------- mappers
fun Task.toEntity() = TaskEntity(id, companyId, parentTaskId, departmentId, title, description, status.key, priority.key,
    createdBy, assignedTo, isOpenPool, eligibleSkill, dueAt, estimatedEffort, approvalChain, approvalStage, blockedReason,
    version, updatedAt, createdAt)

fun TaskEntity.toDomain() = Task(id, companyId, parentTaskId, departmentId, title, description, TaskStatus.of(status),
    Priority.of(priority), createdBy, assignedTo, isOpenPool, eligibleSkill, dueAt, estimatedEffort, approvalChain,
    approvalStage, blockedReason, version, updatedAt, createdAt)

fun Workspace.toEntity() = WorkspaceEntity(companyId, name, slug, industry.key, brandColor, status, roleKey, roleName,
    roleRank, departmentId, permissions.toList(), modules.toList())

fun WorkspaceEntity.toDomain() = Workspace(companyId, name, slug, Industry.of(industry), brandColor, status, roleKey,
    roleName, roleRank, departmentId, permissions.toSet(), modules.toSet())

fun ChecklistItem.toEntity(companyId: String) = ChecklistEntity(id, companyId, taskId, label, done, position)
fun ChecklistEntity.toDomain() = ChecklistItem(id, taskId, label, done, position)
fun Channel.toEntity() = ChannelEntity(id, companyId, name, kind, lastMessage, lastAt)
fun ChannelEntity.toDomain() = Channel(id, companyId, name, kind, 0, lastMessage, lastAt)
fun Message.toEntity() = MessageEntity(id, companyId, channelId, senderId, senderName, body, at, pending)
fun MessageEntity.toDomain() = Message(id, companyId, channelId, senderId, senderName, body, at, pending)
fun AppNotification.toEntity() = NotificationEntity(id, companyId, kind, title, body, taskId, read, at)
fun NotificationEntity.toDomain() = AppNotification(id, companyId, kind, title, body, taskId, read, at)
