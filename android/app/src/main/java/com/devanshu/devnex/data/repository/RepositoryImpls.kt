package com.devanshu.devnex.data.repository

import android.content.Context
import com.devanshu.devnex.core.database.*
import com.devanshu.devnex.core.sync.SyncScheduler
import com.devanshu.devnex.data.remote.RemoteDataSource
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val remote: RemoteDataSource,
    private val db: DevNexDatabase,
) : AuthRepository {
    private val _session = MutableStateFlow<SessionUser?>(null)
    override val session: StateFlow<SessionUser?> = _session.asStateFlow()
    override val isDemo get() = remote.isDemo

    override suspend fun restore() = remote.currentUser().also { _session.value = it }
    override suspend fun signIn(email: String, password: String) = remote.signIn(email, password).also { _session.value = it }
    override suspend fun signUp(email: String, password: String, fullName: String) =
        remote.signUp(email, password, fullName).also { _session.value = it }
    override suspend fun verifyMfa(code: String) {
        remote.verifyMfa(code)
        _session.value = _session.value?.copy(needsMfa = false)
    }
    override suspend fun signOut() {
        remote.signOut()
        withContext(Dispatchers.IO) { db.clearAllTables() }   // nothing from the previous account stays on the device
        _session.value = null
    }
    override suspend fun resetPassword(email: String) = remote.resetPassword(email)
    override suspend fun updateName(fullName: String) {
        remote.updateName(fullName)
        _session.value = _session.value?.copy(fullName = fullName.trim())
    }
}

@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val remote: RemoteDataSource,
    private val db: DevNexDatabase,
) : WorkspaceRepository {
    private val prefs = context.getSharedPreferences("devnex_prefs", Context.MODE_PRIVATE)
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val _active = MutableStateFlow<Workspace?>(null)
    override val workspaces = _workspaces.asStateFlow()
    override val active = _active.asStateFlow()

    override suspend fun refresh(): List<Workspace> {
        val list = runCatching { remote.myWorkspaces().also { db.workspaces().replace(it.map { w -> w.toEntity() }) } }
            .getOrElse { db.workspaces().all().map { it.toDomain() }.ifEmpty { throw it } }   // offline → cache
        _workspaces.value = list
        val saved = prefs.getString("active_company", null)
        _active.value = list.firstOrNull { it.companyId == (_active.value?.companyId ?: saved) && it.isActive }
            ?: list.singleOrNull()?.takeIf { it.isActive }
        return list
    }

    override suspend fun select(companyId: String) {
        val w = _workspaces.value.firstOrNull { it.companyId == companyId } ?: return
        prefs.edit().putString("active_company", companyId).apply()
        _active.value = w          // observers re-scope; realtime collectors restart for the new tenant
    }

    override suspend fun createCompany(name: String, slug: String, industry: Industry): Workspace {
        val id = remote.createCompany(name, slug, industry)
        refresh(); select(id)
        return _active.value!!
    }

    override suspend fun joinWithInvite(token: String): Workspace {
        val id = remote.acceptInvitation(token)
        refresh(); select(id)
        return _active.value!!
    }

    override suspend fun invite(email: String, roleKey: String, departmentId: String?) =
        remote.createInvitation(requireActive().companyId, email, roleKey, departmentId)

    override suspend fun setModule(module: String, enabled: Boolean) {
        remote.setModule(requireActive().companyId, module, enabled)
        refresh()
    }

    override fun clear() { _workspaces.value = emptyList(); _active.value = null; prefs.edit().clear().apply() }

    private fun requireActive() = _active.value ?: throw DomainException("Choose a workspace first")
}

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val remote: RemoteDataSource,
    private val db: DevNexDatabase,
    private val scheduler: SyncScheduler,
) : TaskRepository {
    override fun observeTasks(companyId: String) = db.tasks().observe(companyId).map { l -> l.map { it.toDomain() } }
    override fun observeTask(taskId: String) = db.tasks().observeOne(taskId).map { it?.toDomain() }
    override fun observeChecklist(taskId: String): Flow<List<ChecklistItem>> =
        db.checklist().observe(taskId).map { l -> l.map { it.toDomain() } }
            .onStart { runCatching { refreshChecklist(taskId) } }

    private suspend fun refreshChecklist(taskId: String) {
        val cid = db.tasks().get(taskId)?.companyId ?: return
        db.checklist().upsert(remote.checklist(taskId).map { it.toEntity(cid) })
    }

    /** Delta sync: only rows changed since the newest one we hold. */
    override suspend fun refresh(companyId: String) {
        val since = db.tasks().latest(companyId)
        val changed = remote.tasks(companyId, since)
        db.tasks().upsert(changed.map { it.toEntity() })
    }

    private suspend fun reload(@Suppress("UNUSED_PARAMETER") taskId: String, companyId: String) {
        refresh(companyId)
    }

    override suspend fun activity(taskId: String) = remote.activity(taskId)
    override suspend fun comments(taskId: String) = remote.comments(taskId)

    override suspend fun addComment(taskId: String, body: String) {
        val t = db.tasks().get(taskId) ?: return
        val id = UUID.randomUUID().toString()
        runCatching { remote.addComment(id, t.companyId, taskId, body) }.onFailure {
            db.outbox().put(OutboxEntity(kind = "comment", companyId = t.companyId, refId = id, a = taskId, b = body))
            scheduler.syncSoon()
        }
    }

    override suspend fun create(draft: TaskDraft): Task {
        val t = remote.createTask(draft)
        db.tasks().upsert(listOf(t.toEntity()))
        return t
    }

    // Claims and approvals are online-only: ownership is never granted offline.
    override suspend fun claim(taskId: String): ClaimResult {
        val cid = db.tasks().get(taskId)?.companyId
        val r = remote.claimTask(taskId)
        if (cid != null) reload(taskId, cid)
        return r
    }

    override suspend fun release(taskId: String, reason: String) {
        remote.releaseTask(taskId, reason); db.tasks().get(taskId)?.let { reload(taskId, it.companyId) }
    }

    override suspend fun setStatus(task: Task, status: TaskStatus, note: String?) {
        try { remote.setTaskStatus(task.id, status, task.version, note) }
        finally { reload(task.id, task.companyId) }    // on conflict we pull the winner's version
    }

    override suspend fun decide(taskId: String, approve: Boolean, note: String?): String {
        val r = remote.decideApproval(taskId, approve, note)
        db.tasks().get(taskId)?.let { reload(taskId, it.companyId) }
        return r
    }

    override suspend fun assign(task: Task, userId: String) {
        try { remote.assignTask(task.id, userId, task.version) } finally { reload(task.id, task.companyId) }
    }

    /** Low-conflict write: apply locally first, sync through the outbox. */
    override suspend fun toggleChecklist(item: ChecklistItem) {
        val cid = db.tasks().get(item.taskId)?.companyId ?: return
        db.checklist().setDone(item.id, !item.done)
        db.outbox().put(OutboxEntity(kind = "checklist", companyId = cid, refId = item.id, a = if (!item.done) "1" else "0"))
        scheduler.syncSoon()
    }

    override suspend fun dashboard(companyId: String) = remote.dashboard(companyId)

    override fun live(companyId: String): Flow<String> = remote.changes(companyId, null)
        .onEach { if (it.startsWith("tasks:")) runCatching { refresh(companyId) } }

    override fun pendingSyncCount(): Flow<Int> = db.outbox().pendingCount()

    override suspend fun edit(task: Task, title: String, description: String, priority: Priority, dueAt: Long?) {
        try { remote.updateTask(task.id, title, description, priority, dueAt) } finally { reload(task.id, task.companyId) }
    }

    /** S = 0.40K + 0.25C + 0.20D + 0.15T — a recommendation; the manager decides. */
    override fun suggestAssignees(task: Task, members: List<Member>, allTasks: List<Task>): List<AssigneeSuggestion> {
        val words = (task.title + " " + (task.eligibleSkill ?: "")).lowercase()
        return members.filter { it.availability != "leave" }.map { m ->
            val active = allTasks.count { it.assignedTo == m.userId && it.status.isActive }
            val k = if (m.skills.any { s -> s.isNotBlank() && words.contains(s.lowercase().take(5)) }) 1.0 else 0.35
            val c = (1.0 - active.coerceAtMost(5) / 5.0) * if (m.availability == "busy") 0.5 else 1.0
            val dueSoon = allTasks.count { it.assignedTo == m.userId && it.status.isActive && it.dueAt != null &&
                task.dueAt != null && it.dueAt <= task.dueAt }
            val d = 1.0 - dueSoon.coerceAtMost(4) / 4.0
            val t = if (task.departmentId != null && m.departmentId == task.departmentId) 1.0 else 0.4
            val s = 0.40 * k + 0.25 * c + 0.20 * d + 0.15 * t
            val why = buildList {
                if (k == 1.0) add("skill match")
                add(if (active == 0) "free now" else "$active active")
                if (t == 1.0) add("same team")
                if (m.availability == "busy") add("marked busy")
            }.joinToString(" · ")
            AssigneeSuggestion(m, s * 100, why)
        }.sortedByDescending { it.score }
    }
}

@Singleton
class TeamRepositoryImpl @Inject constructor(private val remote: RemoteDataSource) : TeamRepository {
    override suspend fun members(companyId: String) = remote.members(companyId)
    override suspend fun departments(companyId: String) = remote.departments(companyId)
}

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val remote: RemoteDataSource,
    private val db: DevNexDatabase,
    private val scheduler: SyncScheduler,
    private val auth: AuthRepository,
) : ChatRepository {
    override fun observeChannels(companyId: String) = db.chat().channels(companyId).map { l -> l.map { it.toDomain() } }
    override fun observeMessages(channelId: String) = db.chat().messages(channelId).map { l -> l.map { it.toDomain() } }

    override suspend fun refreshChannels(companyId: String) {
        db.chat().upsertChannels(remote.channels(companyId).map { it.toEntity() })
    }

    /** Recent first, 40 at a time; older pages on demand. */
    override suspend fun loadMessages(channelId: String, olderThan: Long?) {
        db.chat().upsertMessages(remote.messages(channelId, olderThan, 40).map { it.toEntity() })
    }

    override suspend fun send(companyId: String, channelId: String, body: String) {
        val me = auth.session.value ?: return
        val id = UUID.randomUUID().toString()     // client UUID → idempotent retries
        val msg = Message(id, companyId, channelId, me.id, me.fullName, body.trim(), System.currentTimeMillis(), pending = true)
        db.chat().upsertMessages(listOf(msg.toEntity()))
        runCatching { remote.sendMessage(id, companyId, channelId, body.trim()) }
            .onSuccess { db.chat().markSent(id) }
            .onFailure {
                db.outbox().put(OutboxEntity(kind = "message", companyId = companyId, refId = id, a = channelId, b = body.trim()))
                scheduler.syncSoon()
            }
    }

    override suspend fun openDirect(companyId: String, otherUserId: String): String {
        val id = remote.openDirect(companyId, otherUserId)
        runCatching { refreshChannels(companyId) }
        return id
    }

    override fun live(companyId: String, channelId: String): Flow<Unit> =
        remote.changes(companyId, channelId).filter { it == "messages:$channelId" }
            .map { runCatching { loadMessages(channelId) }; Unit }
}

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val remote: RemoteDataSource,
    private val db: DevNexDatabase,
) : NotificationRepository {
    override fun observe(companyId: String) = db.notifications().observe(companyId).map { l -> l.map { it.toDomain() } }
    override suspend fun refresh(companyId: String) {
        db.notifications().upsert(remote.notifications(companyId).map { it.toEntity() })
    }
    override suspend fun markRead(id: String) {
        db.notifications().markRead(id); runCatching { remote.markNotificationRead(id) }
    }
}

@Singleton
class CoverageRepositoryImpl @Inject constructor(private val remote: RemoteDataSource) : CoverageRepository {
    override suspend fun events(companyId: String) = remote.coverageEvents(companyId)
    override suspend fun submissions(eventId: String) = remote.coverageSubmissions(eventId)
    override suspend fun createEvent(companyId: String, name: String, sport: String, venue: String, date: Long, importance: Priority) =
        remote.createCoverageEvent(companyId, name, sport, venue, date, importance)
    override suspend fun submit(companyId: String, eventId: String, athlete: String, result: String, quotes: String, angle: String, links: List<String>) =
        remote.submitCoverage(companyId, eventId, athlete, result, quotes, angle, links)
    override suspend fun setEditorialStatus(submissionId: String, status: String) = remote.setEditorialStatus(submissionId, status)
}

@Singleton
class PlatformRepositoryImpl @Inject constructor(private val remote: RemoteDataSource) : PlatformRepository {
    override suspend fun overview() = remote.platformOverview()
    override suspend fun setStatus(companyId: String, status: String) = remote.platformSetStatus(companyId, status)
}

@Singleton
class ModulesRepositoryImpl @Inject constructor(private val remote: RemoteDataSource) : ModulesRepository {
    override suspend fun slots(companyId: String) = remote.slots(companyId)
    override suspend fun createSlot(companyId: String, title: String, platform: String, ownerId: String?, scheduledAt: Long, caption: String) =
        remote.createSlot(companyId, title, platform, ownerId, scheduledAt, caption)
    override suspend fun setSlotStatus(slotId: String, status: String) = remote.setSlotStatus(slotId, status)
    override suspend fun publishSlot(slotId: String, url: String) = remote.publishSlot(slotId, url)
    override suspend fun ideas(companyId: String) = remote.ideas(companyId)
    override suspend fun createIdea(companyId: String, title: String, description: String, impact: String, ownerId: String?, reviewDate: Long?) =
        remote.createIdea(companyId, title, description, impact, ownerId, reviewDate)
    override suspend fun setIdeaStatus(ideaId: String, status: String) = remote.setIdeaStatus(ideaId, status)
    override suspend fun convertIdea(ideaId: String) = remote.convertIdea(ideaId)
    override suspend fun opsLog(companyId: String) = remote.opsLog(companyId)
    override suspend fun addOps(companyId: String, category: String, body: String) = remote.addOps(companyId, category, body)
}

@Singleton
class AdminRepositoryImpl @Inject constructor(private val remote: RemoteDataSource) : AdminRepository {
    override suspend fun setMemberRole(membershipId: String, roleKey: String) = remote.setMemberRole(membershipId, roleKey)
    override suspend fun setMemberStatus(membershipId: String, status: String) = remote.setMemberStatus(membershipId, status)
    override suspend fun setMemberDepartment(membershipId: String, departmentId: String?) = remote.setMemberDepartment(membershipId, departmentId)
    override suspend fun createDepartment(companyId: String, name: String) = remote.createDepartment(companyId, name)
    override suspend fun deleteDepartment(departmentId: String) = remote.deleteDepartment(departmentId)
    override suspend fun settings(companyId: String) = remote.companySettings(companyId)
    override suspend fun updateSettings(companyId: String, settings: CompanySettings) = remote.updateCompanySettings(companyId, settings)
    override suspend fun audit(companyId: String) = remote.auditLog(companyId)
    override suspend fun invitations(companyId: String) = remote.invitations(companyId)
    override suspend fun revokeInvitation(id: String) = remote.revokeInvitation(id)
    override suspend fun updateMyProfile(companyId: String, title: String, availability: String, skills: List<String>) =
        remote.updateMyMembership(companyId, title, availability, skills)
}
