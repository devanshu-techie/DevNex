package com.devanshu.devnex.data.remote

import com.devanshu.devnex.domain.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Production backend. Reads are plain selects protected by RLS; every important
 * state change is an RPC to a SECURITY DEFINER function in the migration.
 * Only the public anon key is used — never service_role.
 */
class SupabaseRemoteDataSource(private val client: SupabaseClient) : RemoteDataSource {
    override val isDemo = false
    private val cleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun ts(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun uid() = client.auth.currentUserOrNull()?.id ?: throw DomainException("Please sign in")

    private suspend fun rpcText(fn: String, params: kotlinx.serialization.json.JsonObject): String =
        client.postgrest.rpc(fn, params).data.trim('"')

    private fun mapError(e: Throwable): Throwable {
        val m = e.message.orEmpty()
        return when {
            "VERSION_CONFLICT" in m -> VersionConflictException()
            "you cannot approve your own work" in m -> DomainException("You cannot approve your own work")
            "Invalid login credentials" in m -> DomainException("Incorrect email or password")
            else -> DomainException(m.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Something went wrong")
        }
    }
    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch (e: Exception) { throw mapError(e) }

    // ---------------------------------------------------------------- auth
    private suspend fun session(): SessionUser? {
        val u = client.auth.currentUserOrNull() ?: return null
        val isAdmin = client.from("platform_admins").select { filter { eq("user_id", u.id) } }
            .decodeList<PlatformAdminDto>().isNotEmpty()
        val profile = client.from("profiles").select { filter { eq("id", u.id) } }.decodeSingleOrNull<ProfileDto>()
        val aal = client.auth.mfa.getAuthenticatorAssuranceLevel()
        val needsMfa = aal.next.name == "AAL2" && aal.current.name != "AAL2"
        return SessionUser(u.id, u.email.orEmpty(), profile?.fullName ?: u.email.orEmpty(), isAdmin, needsMfa)
    }

    override suspend fun currentUser(): SessionUser? = runCatching { session() }.getOrNull()

    override suspend fun signIn(email: String, password: String): SessionUser = call {
        client.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
        session() ?: throw DomainException("Sign in failed")
    }

    override suspend fun signUp(email: String, password: String, fullName: String): SessionUser = call {
        client.auth.signUpWith(Email) {
            this.email = email.trim(); this.password = password
            data = buildJsonObject { put("full_name", fullName.trim()) }
        }
        session() ?: throw DomainException("Check your inbox to confirm your email, then sign in")
    }

    override suspend fun verifyMfa(code: String) = call {
        val factor = client.auth.mfa.verifiedFactors.firstOrNull() ?: throw DomainException("No authenticator is enrolled")
        client.auth.mfa.createChallengeAndVerify(factor.id, code)
        Unit
    }

    override suspend fun signOut() { runCatching { client.auth.signOut() } }

    // ---------------------------------------------------------------- workspaces
    override suspend fun myWorkspaces(): List<Workspace> = call {
        client.postgrest.rpc("my_workspaces").decodeList<WorkspaceDto>().map {
            Workspace(it.companyId, it.companyName, it.slug, Industry.of(it.industry), parseColor(it.brandColor),
                it.companyStatus, it.roleKey, it.roleName, it.roleRank, it.departmentId, it.permissions.toSet(), it.modules.toSet())
        }
    }

    override suspend fun createCompany(name: String, slug: String, industry: Industry): String = call {
        rpcText("create_company", buildJsonObject { put("p_name", name); put("p_slug", slug); put("p_industry", industry.key) })
    }

    override suspend fun acceptInvitation(token: String) = call {
        rpcText("accept_invitation", buildJsonObject { put("p_token", token.trim()) })
    }

    override suspend fun createInvitation(companyId: String, email: String, roleKey: String, departmentId: String?) = call {
        rpcText("create_invitation", buildJsonObject {
            put("p_company", companyId); put("p_email", email); put("p_role_key", roleKey); put("p_department", departmentId)
        })
    }

    override suspend fun setModule(companyId: String, module: String, enabled: Boolean) = call {
        client.from("company_modules").update(buildJsonObject { put("enabled", enabled) }) {
            filter { eq("company_id", companyId); eq("module", module) }
        }
        Unit
    }

    // ---------------------------------------------------------------- team
    override suspend fun members(companyId: String): List<Member> = call {
        val roles = client.from("company_roles").select { filter { eq("company_id", companyId) } }.decodeList<RoleDto>()
            .associateBy { it.id }
        val ms = client.from("company_memberships").select { filter { eq("company_id", companyId); eq("status", "active") } }
            .decodeList<MembershipDto>()
        val profiles = client.from("profiles").select { filter { isIn("id", ms.map { it.userId }) } }
            .decodeList<ProfileDto>().associateBy { it.id }
        ms.map { m ->
            val r = roles[m.roleId]; val p = profiles[m.userId]
            Member(m.userId, m.id, companyId, p?.fullName ?: "Member", p?.email.orEmpty(), r?.key ?: "member",
                r?.name ?: "Member", r?.rank ?: 10, m.departmentId, m.title, m.skills, m.availability)
        }.sortedByDescending { it.roleRank }
    }

    override suspend fun departments(companyId: String) = call {
        client.from("company_departments").select { filter { eq("company_id", companyId) }; order("name", Order.ASCENDING) }
            .decodeList<DepartmentDto>().map { Department(it.id, it.companyId, it.name) }
    }

    // ---------------------------------------------------------------- tasks
    override suspend fun tasks(companyId: String, updatedAfter: Long?): List<Task> = call {
        client.from("tasks").select {
            filter {
                eq("company_id", companyId)
                if (updatedAfter != null) gt("updated_at", iso(updatedAfter))
            }
            order("updated_at", Order.DESCENDING)
            limit(500)
        }.decodeList<TaskDto>().map { it.toDomain() }
    }

    override suspend fun createTask(draft: TaskDraft): Task = call {
        val canCreate = myWorkspaces().firstOrNull { it.companyId == draft.companyId }?.can(Perm.TASK_CREATE) == true
        val status = when {
            !canCreate -> "inbox"; draft.openPool -> "open"; draft.assignedTo != null -> "assigned"; else -> "inbox"
        }
        val body = buildJsonObject {
            put("company_id", draft.companyId); put("title", draft.title.trim()); put("description", draft.description.trim())
            put("priority", draft.priority.key); put("department_id", draft.departmentId); put("status", status)
            put("created_by", uid()); put("assigned_to", if (canCreate) draft.assignedTo else null)
            put("is_open_pool", canCreate && draft.openPool); put("parent_task_id", draft.parentTaskId)
            put("due_at", draft.dueAt?.let { iso(it) })
            putJsonArray("approval_chain") { draft.approvalChain.forEach { add(it) } }
        }
        val created = client.from("tasks").insert(body) { select() }.decodeSingle<TaskDto>()
        if (draft.checklist.isNotEmpty()) {
            client.from("task_checklists").insert(draft.checklist.filter { it.isNotBlank() }.mapIndexed { i, l ->
                buildJsonObject { put("company_id", draft.companyId); put("task_id", created.id); put("label", l); put("position", i) }
            })
        }
        created.toDomain()
    }

    override suspend fun claimTask(taskId: String) = call {
        ClaimResult.of(rpcText("claim_task", buildJsonObject { put("p_task_id", taskId) }))
    }

    override suspend fun releaseTask(taskId: String, reason: String) = call {
        client.postgrest.rpc("release_task", buildJsonObject { put("p_task_id", taskId); put("p_reason", reason) })
        Unit
    }

    override suspend fun setTaskStatus(taskId: String, status: TaskStatus, expectedVersion: Int, note: String?) = call {
        rpcText("set_task_status", buildJsonObject {
            put("p_task_id", taskId); put("p_status", status.key); put("p_expected_version", expectedVersion); put("p_note", note)
        }).toInt()
    }

    override suspend fun decideApproval(taskId: String, approve: Boolean, note: String?) = call {
        rpcText("decide_approval", buildJsonObject {
            put("p_task_id", taskId); put("p_decision", if (approve) "approved" else "changes_requested"); put("p_note", note)
        })
    }

    override suspend fun assignTask(taskId: String, userId: String, expectedVersion: Int) = call {
        rpcText("assign_task", buildJsonObject {
            put("p_task_id", taskId); put("p_user", userId); put("p_expected_version", expectedVersion)
        }).toInt()
    }

    override suspend fun checklist(taskId: String) = call {
        client.from("task_checklists").select { filter { eq("task_id", taskId) }; order("position", Order.ASCENDING) }
            .decodeList<ChecklistDto>().map { ChecklistItem(it.id, it.taskId, it.label, it.isDone, it.position) }
    }

    override suspend fun toggleChecklist(itemId: String, done: Boolean) = call {
        client.postgrest.rpc("toggle_checklist", buildJsonObject { put("p_item", itemId); put("p_done", done) })
        Unit
    }

    override suspend fun activity(taskId: String) = call {
        val rows = client.from("task_activity").select { filter { eq("task_id", taskId) }; order("created_at", Order.DESCENDING); limit(100) }
            .decodeList<ActivityDto>()
        val names = names(rows.mapNotNull { it.actorId })
        rows.map { TaskActivity(it.id.toString(), it.taskId, names[it.actorId] ?: "System", it.action, it.detail.toString(), ts(it.createdAt) ?: 0) }
    }

    override suspend fun comments(taskId: String) = call {
        val rows = client.from("task_comments").select { filter { eq("task_id", taskId) }; order("created_at", Order.ASCENDING) }
            .decodeList<CommentDto>()
        val names = names(rows.map { it.authorId })
        rows.map { Comment(it.id, it.taskId, it.authorId, names[it.authorId] ?: "Member", it.body, ts(it.createdAt) ?: 0) }
    }

    override suspend fun addComment(id: String, companyId: String, taskId: String, body: String) = call {
        client.from("task_comments").upsert(buildJsonObject {
            put("id", id); put("company_id", companyId); put("task_id", taskId); put("author_id", uid()); put("body", body)
        }) { ignoreDuplicates = true }
        Unit
    }

    override suspend fun dashboard(companyId: String) = call {
        val d = client.postgrest.rpc("dashboard_summary", buildJsonObject { put("p_company", companyId) }).decodeAs<DashboardDto>()
        DashboardStats(d.scope, d.active, d.overdue, d.awaitingReview, d.blocked, d.openPool, d.doneToday, d.doneWeek)
    }

    private suspend fun names(ids: List<String>): Map<String, String> =
        if (ids.isEmpty()) emptyMap()
        else client.from("profiles").select { filter { isIn("id", ids.distinct()) } }.decodeList<ProfileDto>()
            .associate { it.id to it.fullName }

    // ---------------------------------------------------------------- chat
    override suspend fun channels(companyId: String) = call {
        client.from("chat_channels").select { filter { eq("company_id", companyId) } }.decodeList<ChannelDto>()
            .map { Channel(it.id, it.companyId, it.name, it.kind) }
    }

    override suspend fun messages(channelId: String, olderThan: Long?, limit: Int) = call {
        val rows = client.from("messages").select {
            filter { eq("channel_id", channelId); if (olderThan != null) lt("created_at", iso(olderThan)) }
            order("created_at", Order.DESCENDING); limit(limit.toLong())
        }.decodeList<MessageDto>()
        val names = names(rows.map { it.senderId })
        rows.map { Message(it.id, it.companyId, it.channelId, it.senderId, names[it.senderId] ?: "Member", it.body, ts(it.createdAt) ?: 0) }
    }

    override suspend fun sendMessage(id: String, companyId: String, channelId: String, body: String) = call {
        client.from("messages").upsert(buildJsonObject {
            put("id", id); put("company_id", companyId); put("channel_id", channelId); put("sender_id", uid()); put("body", body)
        }) { ignoreDuplicates = true }   // client UUID → a retry never duplicates
        Unit
    }

    /** Scoped realtime: one channel per open screen, torn down when the collector stops. */
    override fun changes(companyId: String, channelId: String?): Flow<String> = callbackFlow {
        val rt: RealtimeChannel = client.channel("devnex-$companyId-${channelId ?: "tasks"}")
        rt.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "tasks"; filter("company_id", FilterOperator.EQ, companyId)
        }.onEach { trySend("tasks:$companyId") }.launchIn(this)
        rt.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "notifications"; filter("company_id", FilterOperator.EQ, companyId)
        }.onEach { trySend("notifications") }.launchIn(this)
        if (channelId != null) {
            rt.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "messages"; filter("channel_id", FilterOperator.EQ, channelId)
            }.onEach { trySend("messages:$channelId") }.launchIn(this)
        }
        launch { runCatching { rt.subscribe() } }
        awaitClose { cleanup.launch { runCatching { client.realtime.removeChannel(rt) } } }
    }

    // ---------------------------------------------------------------- notifications
    override suspend fun notifications(companyId: String) = call {
        client.from("notifications").select {
            filter { eq("company_id", companyId) }; order("created_at", Order.DESCENDING); limit(100)
        }.decodeList<NotificationDto>().map {
            AppNotification(it.id.toString(), it.companyId, it.kind, it.title, it.body, it.taskId, it.readAt != null, ts(it.createdAt) ?: 0)
        }
    }

    override suspend fun markNotificationRead(id: String) = call {
        client.from("notifications").update(buildJsonObject { put("read_at", Instant.now().toString()) }) {
            filter { eq("id", id.toLong()) }
        }
        Unit
    }

    // ---------------------------------------------------------------- coverage
    override suspend fun coverageEvents(companyId: String) = call {
        client.from("coverage_events").select { filter { eq("company_id", companyId) }; order("event_date", Order.DESCENDING) }
            .decodeList<EventDto>().map {
                CoverageEvent(it.id, it.companyId, it.name, it.sport, it.venue,
                    runCatching { java.time.LocalDate.parse(it.eventDate).toEpochDay() * 86_400_000L }.getOrDefault(0),
                    Priority.of(it.importance), it.status)
            }
    }

    override suspend fun coverageSubmissions(eventId: String) = call {
        val rows = client.from("coverage_submissions").select { filter { eq("event_id", eventId) } }.decodeList<SubmissionDto>()
        val names = names(rows.map { it.reporterId })
        rows.map {
            CoverageSubmission(it.id, it.eventId, names[it.reporterId] ?: "Reporter", it.athlete, it.result, it.quotes,
                it.newsAngle, it.mediaLinks, it.verified,
                mapOf('F' to (it.scoreF ?: 0), 'I' to (it.scoreI ?: 0), 'U' to (it.scoreU ?: 0),
                      'A' to (it.scoreA ?: 0), 'R' to (it.scoreR ?: 0), 'V' to (it.scoreV ?: 0)),
                it.editorialStatus, ts(it.createdAt) ?: 0)
        }.sortedByDescending { it.editorialScore() }
    }

    override suspend fun createCoverageEvent(companyId: String, name: String, sport: String, venue: String, date: Long, importance: Priority) = call {
        client.from("coverage_events").insert(buildJsonObject {
            put("company_id", companyId); put("name", name); put("sport", sport); put("venue", venue)
            put("event_date", java.time.LocalDate.ofEpochDay(date / 86_400_000L).toString())
            put("importance", importance.key); put("created_by", uid())
        })
        Unit
    }

    override suspend fun submitCoverage(companyId: String, eventId: String, athlete: String, result: String, quotes: String, angle: String, links: List<String>) = call {
        client.from("coverage_submissions").insert(buildJsonObject {
            put("company_id", companyId); put("event_id", eventId); put("reporter_id", uid()); put("athlete", athlete)
            put("result", result); put("quotes", quotes); put("news_angle", angle)
            putJsonArray("media_links") { links.forEach { add(it) } }
        })
        Unit
    }

    override suspend fun setEditorialStatus(submissionId: String, status: String) = call {
        client.from("coverage_submissions").update(buildJsonObject { put("editorial_status", status) }) {
            filter { eq("id", submissionId) }
        }
        Unit
    }

    // ---------------------------------------------------------------- platform
    override suspend fun platformOverview() = call {
        client.postgrest.rpc("platform_overview").decodeList<OverviewDto>().map {
            CompanyOverview(it.companyId, it.name, it.industry, it.status, it.plan, it.members.toInt(), it.tasks.toInt(), ts(it.createdAt) ?: 0)
        }
    }

    override suspend fun platformSetStatus(companyId: String, status: String) = call {
        client.postgrest.rpc("platform_set_company_status", buildJsonObject { put("p_company", companyId); put("p_status", status) })
        Unit
    }

    private fun parseColor(hex: String): Long =
        runCatching { 0xFF000000 or hex.removePrefix("#").toLong(16) }.getOrDefault(0xFF7C5CFF)

    private fun TaskDto.toDomain() = Task(id, companyId, parentTaskId, departmentId, title, description, TaskStatus.of(status),
        Priority.of(priority), createdBy, assignedTo, isOpenPool, eligibleSkill, ts(dueAt), estimatedEffort, approvalChain,
        approvalStage, blockedReason, version, ts(updatedAt) ?: 0, ts(createdAt) ?: 0)
}

// ---------------------------------------------------------------- DTOs (snake_case ↔ camelCase)
@Serializable data class PlatformAdminDto(@SerialName("user_id") val userId: String)
@Serializable data class ProfileDto(val id: String, val email: String = "", @SerialName("full_name") val fullName: String = "")
@Serializable data class RoleDto(val id: String, val key: String, val name: String, val rank: Int)
@Serializable data class DepartmentDto(val id: String, @SerialName("company_id") val companyId: String, val name: String)
@Serializable data class MembershipDto(
    val id: String, @SerialName("user_id") val userId: String, @SerialName("role_id") val roleId: String,
    @SerialName("department_id") val departmentId: String? = null, val title: String = "",
    val skills: List<String> = emptyList(), val availability: String = "available",
)
@Serializable data class WorkspaceDto(
    @SerialName("company_id") val companyId: String, @SerialName("company_name") val companyName: String,
    val slug: String, val industry: String, @SerialName("brand_color") val brandColor: String,
    @SerialName("company_status") val companyStatus: String, @SerialName("role_key") val roleKey: String,
    @SerialName("role_name") val roleName: String, @SerialName("role_rank") val roleRank: Int,
    @SerialName("department_id") val departmentId: String? = null,
    val permissions: List<String> = emptyList(), val modules: List<String> = emptyList(),
)
@Serializable data class TaskDto(
    val id: String, @SerialName("company_id") val companyId: String,
    @SerialName("parent_task_id") val parentTaskId: String? = null, @SerialName("department_id") val departmentId: String? = null,
    val title: String, val description: String = "", val status: String, val priority: String,
    @SerialName("created_by") val createdBy: String, @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("is_open_pool") val isOpenPool: Boolean = false, @SerialName("eligible_skill") val eligibleSkill: String? = null,
    @SerialName("due_at") val dueAt: String? = null, @SerialName("estimated_effort") val estimatedEffort: Double? = null,
    @SerialName("approval_chain") val approvalChain: List<String> = emptyList(), @SerialName("approval_stage") val approvalStage: Int = 0,
    @SerialName("blocked_reason") val blockedReason: String? = null, val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null, @SerialName("created_at") val createdAt: String? = null,
)
@Serializable data class ChecklistDto(val id: String, @SerialName("task_id") val taskId: String, val label: String,
    @SerialName("is_done") val isDone: Boolean, val position: Int)
@Serializable data class ActivityDto(val id: Long, @SerialName("task_id") val taskId: String, @SerialName("actor_id") val actorId: String? = null,
    val action: String, val detail: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null)
@Serializable data class CommentDto(val id: String, @SerialName("task_id") val taskId: String, @SerialName("author_id") val authorId: String,
    val body: String, @SerialName("created_at") val createdAt: String? = null)
@Serializable data class DashboardDto(
    val scope: String = "personal", val active: Int = 0, val overdue: Int = 0,
    @SerialName("awaiting_review") val awaitingReview: Int = 0, val blocked: Int = 0,
    @SerialName("open_pool") val openPool: Int = 0, @SerialName("done_today") val doneToday: Int = 0,
    @SerialName("done_week") val doneWeek: Int = 0,
)
@Serializable data class ChannelDto(val id: String, @SerialName("company_id") val companyId: String, val name: String, val kind: String)
@Serializable data class MessageDto(val id: String, @SerialName("company_id") val companyId: String, @SerialName("channel_id") val channelId: String,
    @SerialName("sender_id") val senderId: String, val body: String, @SerialName("created_at") val createdAt: String? = null)
@Serializable data class NotificationDto(val id: Long, @SerialName("company_id") val companyId: String, val kind: String, val title: String,
    val body: String = "", @SerialName("task_id") val taskId: String? = null, @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null)
@Serializable data class EventDto(val id: String, @SerialName("company_id") val companyId: String, val name: String, val sport: String = "",
    val venue: String = "", @SerialName("event_date") val eventDate: String, val importance: String = "normal", val status: String = "planned")
@Serializable data class SubmissionDto(
    val id: String, @SerialName("event_id") val eventId: String, @SerialName("reporter_id") val reporterId: String,
    val athlete: String = "", val result: String = "", val quotes: String = "", @SerialName("news_angle") val newsAngle: String = "",
    @SerialName("media_links") val mediaLinks: List<String> = emptyList(), val verified: Boolean = false,
    @SerialName("score_f") val scoreF: Int? = null, @SerialName("score_i") val scoreI: Int? = null,
    @SerialName("score_u") val scoreU: Int? = null, @SerialName("score_a") val scoreA: Int? = null,
    @SerialName("score_r") val scoreR: Int? = null, @SerialName("score_v") val scoreV: Int? = null,
    @SerialName("editorial_status") val editorialStatus: String = "submitted", @SerialName("created_at") val createdAt: String? = null,
)
@Serializable data class OverviewDto(@SerialName("company_id") val companyId: String, val name: String, val industry: String,
    val status: String, val plan: String, val members: Long, val tasks: Long, @SerialName("created_at") val createdAt: String? = null)
