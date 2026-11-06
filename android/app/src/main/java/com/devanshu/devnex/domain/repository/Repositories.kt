package com.devanshu.devnex.domain.repository

import com.devanshu.devnex.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contracts. The UI only ever talks to these (through ViewModels);
 * it never touches Supabase, SQL or Room directly. Swapping the backend
 * (shards, dedicated databases) only changes the implementations.
 */
interface AuthRepository {
    val session: StateFlow<SessionUser?>
    val isDemo: Boolean
    suspend fun restore(): SessionUser?
    suspend fun signIn(email: String, password: String): SessionUser
    suspend fun signUp(email: String, password: String, fullName: String): SessionUser
    suspend fun verifyMfa(code: String)
    suspend fun signOut()
}

interface WorkspaceRepository {
    val workspaces: StateFlow<List<Workspace>>
    val active: StateFlow<Workspace?>
    suspend fun refresh(): List<Workspace>
    suspend fun select(companyId: String)
    suspend fun createCompany(name: String, slug: String, industry: Industry): Workspace
    suspend fun joinWithInvite(token: String): Workspace
    suspend fun invite(email: String, roleKey: String, departmentId: String?): String
    suspend fun setModule(module: String, enabled: Boolean)
    fun clear()
}

interface TaskRepository {
    fun observeTasks(companyId: String): Flow<List<Task>>
    fun observeTask(taskId: String): Flow<Task?>
    fun observeChecklist(taskId: String): Flow<List<ChecklistItem>>
    suspend fun refresh(companyId: String)
    suspend fun activity(taskId: String): List<TaskActivity>
    suspend fun comments(taskId: String): List<Comment>
    suspend fun addComment(taskId: String, body: String)
    suspend fun create(draft: TaskDraft): Task
    suspend fun claim(taskId: String): ClaimResult
    suspend fun release(taskId: String, reason: String)
    suspend fun setStatus(task: Task, status: TaskStatus, note: String? = null)
    suspend fun decide(taskId: String, approve: Boolean, note: String?): String
    suspend fun assign(task: Task, userId: String)
    suspend fun toggleChecklist(item: ChecklistItem)
    suspend fun dashboard(companyId: String): DashboardStats
    fun suggestAssignees(task: Task, members: List<Member>, allTasks: List<Task>): List<AssigneeSuggestion>
    /** Scoped realtime for the active company; emits after the local cache has been refreshed. */
    fun live(companyId: String): Flow<String>
    fun pendingSyncCount(): Flow<Int>
}

interface TeamRepository {
    suspend fun members(companyId: String): List<Member>
    suspend fun departments(companyId: String): List<Department>
}

interface ChatRepository {
    fun observeChannels(companyId: String): Flow<List<Channel>>
    fun observeMessages(channelId: String): Flow<List<Message>>
    suspend fun refreshChannels(companyId: String)
    suspend fun loadMessages(channelId: String, olderThan: Long? = null)
    suspend fun send(companyId: String, channelId: String, body: String)
    fun live(companyId: String, channelId: String): Flow<Unit>
}

interface NotificationRepository {
    fun observe(companyId: String): Flow<List<AppNotification>>
    suspend fun refresh(companyId: String)
    suspend fun markRead(id: String)
}

interface CoverageRepository {
    suspend fun events(companyId: String): List<CoverageEvent>
    suspend fun submissions(eventId: String): List<CoverageSubmission>
    suspend fun createEvent(companyId: String, name: String, sport: String, venue: String, date: Long, importance: Priority)
    suspend fun submit(companyId: String, eventId: String, athlete: String, result: String, quotes: String, angle: String, links: List<String>)
    suspend fun setEditorialStatus(submissionId: String, status: String)
}

interface PlatformRepository {
    suspend fun overview(): List<CompanyOverview>
    suspend fun setStatus(companyId: String, status: String)
}
