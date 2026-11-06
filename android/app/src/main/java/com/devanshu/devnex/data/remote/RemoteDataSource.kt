package com.devanshu.devnex.data.remote

import com.devanshu.devnex.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the app and a backend.
 * - [com.devanshu.devnex.data.demo.DemoRemoteDataSource]: in-memory backend that enforces the
 *   same rules as the SQL functions, so the app is fully usable before Supabase is connected.
 * - [SupabaseRemoteDataSource]: production. All protected writes go through RPC functions.
 * A future tenant router (shards / dedicated DBs) is another implementation of this interface.
 */
interface RemoteDataSource {
    val isDemo: Boolean

    // auth
    suspend fun currentUser(): SessionUser?
    suspend fun signIn(email: String, password: String): SessionUser
    suspend fun signUp(email: String, password: String, fullName: String): SessionUser
    suspend fun verifyMfa(code: String)
    suspend fun signOut()

    // workspaces
    suspend fun myWorkspaces(): List<Workspace>
    suspend fun createCompany(name: String, slug: String, industry: Industry): String
    suspend fun acceptInvitation(token: String): String
    suspend fun createInvitation(companyId: String, email: String, roleKey: String, departmentId: String?): String
    suspend fun setModule(companyId: String, module: String, enabled: Boolean)

    // team
    suspend fun members(companyId: String): List<Member>
    suspend fun departments(companyId: String): List<Department>

    // tasks
    suspend fun tasks(companyId: String, updatedAfter: Long?): List<Task>
    suspend fun createTask(draft: TaskDraft): Task
    suspend fun claimTask(taskId: String): ClaimResult
    suspend fun releaseTask(taskId: String, reason: String)
    suspend fun setTaskStatus(taskId: String, status: TaskStatus, expectedVersion: Int, note: String?): Int
    suspend fun decideApproval(taskId: String, approve: Boolean, note: String?): String
    suspend fun assignTask(taskId: String, userId: String, expectedVersion: Int): Int
    suspend fun checklist(taskId: String): List<ChecklistItem>
    suspend fun toggleChecklist(itemId: String, done: Boolean)
    suspend fun activity(taskId: String): List<TaskActivity>
    suspend fun comments(taskId: String): List<Comment>
    suspend fun addComment(id: String, companyId: String, taskId: String, body: String)
    suspend fun dashboard(companyId: String): DashboardStats

    // chat (text only)
    suspend fun channels(companyId: String): List<Channel>
    suspend fun messages(channelId: String, olderThan: Long?, limit: Int): List<Message>
    suspend fun sendMessage(id: String, companyId: String, channelId: String, body: String)
    /** Emits whenever something changes for the given scope (tasks/messages/notifications). */
    fun changes(companyId: String, channelId: String?): Flow<String>

    // notifications
    suspend fun notifications(companyId: String): List<AppNotification>
    suspend fun markNotificationRead(id: String)

    // coverage desk
    suspend fun coverageEvents(companyId: String): List<CoverageEvent>
    suspend fun coverageSubmissions(eventId: String): List<CoverageSubmission>
    suspend fun createCoverageEvent(companyId: String, name: String, sport: String, venue: String, date: Long, importance: Priority)
    suspend fun submitCoverage(companyId: String, eventId: String, athlete: String, result: String, quotes: String, angle: String, links: List<String>)
    suspend fun setEditorialStatus(submissionId: String, status: String)

    // platform console
    suspend fun platformOverview(): List<CompanyOverview>
    suspend fun platformSetStatus(companyId: String, status: String)
}
