package com.devanshu.devnex.data.demo

import com.devanshu.devnex.data.remote.RemoteDataSource
import com.devanshu.devnex.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * A complete in-memory backend used when no Supabase keys are configured.
 * It mirrors the server rules (tenant isolation, atomic claim, approval chain,
 * no self-approval, optimistic versions) so the UI behaves exactly as in production.
 *
 * Demo logins (password for all: devnex123):
 *   owner@devnex.app     Devanshu — platform owner, company owner (MFA code 123456)
 *   head@devnex.app      Priya    — Editorial head
 *   lead@devnex.app      Arjun    — Reporting team lead
 *   reporter@devnex.app  Kabir    — Reporter (member)
 *   editor@devnex.app    Neha     — Video editor (member)
 */
class DemoRemoteDataSource : RemoteDataSource {
    override val isDemo = true

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // ---------------------------------------------------------------- state
    private data class U(val id: String, val email: String, val name: String, val platform: Boolean)
    private data class Co(val id: String, var name: String, val slug: String, val industry: Industry,
                          var status: String, val color: Long, val modules: MutableSet<String>, val createdAt: Long)
    private data class Role(val key: String, val name: String, val rank: Int, val perms: Set<String>)
    private data class Mem(val id: String, val companyId: String, val userId: String, var roleKey: String,
                           var departmentId: String?, var title: String, var skills: List<String>,
                           var availability: String = "available")
    private data class Ch(val id: String, val companyId: String, val name: String, val kind: String)
    private data class Inv(val token: String, val companyId: String, val email: String, val roleKey: String,
                           val departmentId: String?, var used: Boolean = false)

    private val users = mutableListOf<U>()
    private val companies = mutableListOf<Co>()
    private val members = mutableListOf<Mem>()
    private val departments = mutableListOf<Department>()
    private val tasks = mutableListOf<Task>()
    private val checklists = mutableListOf<ChecklistItem>()
    private val activity = mutableListOf<TaskActivity>()
    private val commentsList = mutableListOf<Comment>()
    private val channels = mutableListOf<Ch>()
    private val messagesList = mutableListOf<Message>()
    private val notes = mutableListOf<AppNotification>()
    private val events = mutableListOf<CoverageEvent>()
    private val submissionsList = mutableListOf<CoverageSubmission>()
    private val invitations = mutableListOf<Inv>()

    private var me: U? = null
    private var mfaPassed = false

    private val allPerms = setOf(
        Perm.COMPANY_MANAGE, Perm.MEMBER_INVITE, Perm.MEMBER_MANAGE, Perm.DEPARTMENT_MANAGE, "role.manage",
        Perm.MODULE_MANAGE, Perm.TASK_CREATE, Perm.TASK_REQUEST, Perm.TASK_EDIT, Perm.TASK_ASSIGN, Perm.TASK_CLAIM,
        Perm.TASK_APPROVE, Perm.TASK_VIEW_ALL, Perm.REPORT_COMPANY, Perm.REPORT_DEPARTMENT, Perm.AUDIT_VIEW,
        Perm.CHAT_ANNOUNCE, Perm.COVERAGE_MANAGE, Perm.COVERAGE_SUBMIT)
    private val roles = listOf(
        Role("owner", "Company Owner", 100, allPerms),
        Role("admin", "Company Admin", 90, allPerms),
        Role("head", "Department Head", 70, setOf(Perm.TASK_CREATE, Perm.TASK_EDIT, Perm.TASK_ASSIGN, Perm.TASK_CLAIM,
            Perm.TASK_APPROVE, Perm.TASK_VIEW_ALL, Perm.REPORT_DEPARTMENT, Perm.COVERAGE_MANAGE, Perm.COVERAGE_SUBMIT, Perm.MEMBER_INVITE)),
        Role("lead", "Team Lead", 50, setOf(Perm.TASK_CREATE, Perm.TASK_EDIT, Perm.TASK_ASSIGN, Perm.TASK_CLAIM,
            Perm.TASK_APPROVE, Perm.REPORT_DEPARTMENT, Perm.COVERAGE_SUBMIT)),
        Role("member", "Member", 10, setOf(Perm.TASK_REQUEST, Perm.TASK_CLAIM, Perm.COVERAGE_SUBMIT)),
    )
    private fun role(key: String) = roles.first { it.key == key }

    private fun now() = System.currentTimeMillis()
    private fun h(n: Double) = (n * 3_600_000).toLong()
    private fun uuid() = UUID.randomUUID().toString()

    init { seed() }

    // ---------------------------------------------------------------- helpers (call inside lock)
    private fun uid(): String = me?.id ?: throw DomainException("Please sign in")
    private fun membership(cid: String, userId: String = uid()) =
        members.firstOrNull { it.companyId == cid && it.userId == userId }
    private fun activeCompany(cid: String) = companies.firstOrNull { it.id == cid && it.status == "active" }
    private fun isMember(cid: String) = activeCompany(cid) != null && membership(cid) != null
    private fun can(cid: String, perm: String): Boolean {
        if (activeCompany(cid) == null) return false
        val m = membership(cid) ?: return false
        return perm in role(m.roleKey).perms
    }
    private fun rank(cid: String) = membership(cid)?.let { role(it.roleKey).rank } ?: -1
    private fun nameOf(userId: String?) = users.firstOrNull { it.id == userId }?.name ?: "Someone"
    private fun taskIndex(id: String) = tasks.indexOfFirst { it.id == id }.also {
        if (it < 0) throw DomainException("Task not found")
    }
    private fun canView(t: Task): Boolean {
        if (!isMember(t.companyId)) return false
        val u = uid()
        return can(t.companyId, Perm.TASK_VIEW_ALL) || t.assignedTo == u || t.createdBy == u ||
            (t.isOpenPool && t.status == TaskStatus.OPEN) ||
            (t.departmentId != null && t.departmentId == membership(t.companyId)?.departmentId)
    }
    private fun log(t: Task, action: String, detail: String = "") {
        activity += TaskActivity(uuid(), t.id, nameOf(me?.id), action, detail, now())
    }
    private fun notify(cid: String, userId: String?, kind: String, title: String, body: String, taskId: String?) {
        if (userId == null || userId == me?.id) return
        notes += AppNotification(uuid(), cid, kind, title, body, taskId, false, now())
            .copy(id = "$userId|${uuid()}")
    }
    private fun bump(t: Task, f: (Task) -> Task): Task {
        val i = taskIndex(t.id)
        val n = f(t).copy(version = t.version + 1, updatedAt = now())
        tasks[i] = n
        bus.tryEmit("tasks:${t.companyId}")
        return n
    }

    // ---------------------------------------------------------------- auth
    override suspend fun currentUser(): SessionUser? = lock.withLock { me?.toSession() }

    private fun U.toSession() = SessionUser(id, email, name, platform,
        needsMfa = !mfaPassed && members.any { it.userId == id && role(it.roleKey).rank >= 90 })

    override suspend fun signIn(email: String, password: String): SessionUser = lock.withLock {
        delay(450)
        val u = users.firstOrNull { it.email.equals(email.trim(), true) }
            ?: throw DomainException("No account found for that email")
        if (password != "devnex123") throw DomainException("Incorrect password")
        me = u; mfaPassed = false
        u.toSession()
    }

    override suspend fun signUp(email: String, password: String, fullName: String): SessionUser = lock.withLock {
        delay(450)
        if (password.length < 8) throw DomainException("Use at least 8 characters")
        if (users.any { it.email.equals(email.trim(), true) }) throw DomainException("That email is already registered")
        val u = U(uuid(), email.trim().lowercase(), fullName.trim(), false)
        users += u; me = u; mfaPassed = true
        u.toSession()
    }

    override suspend fun verifyMfa(code: String) {
        lock.withLock {
        delay(350)
        if (code != "123456") throw DomainException("That code didn't match. In demo mode use 123456")
        mfaPassed = true
            Unit
        }
    }

    override suspend fun signOut() {
        lock.withLock { me = null; mfaPassed = false
            Unit
        }
    }

    // ---------------------------------------------------------------- workspaces
    override suspend fun myWorkspaces(): List<Workspace> = lock.withLock {
        val u = uid()
        members.filter { it.userId == u }.mapNotNull { m ->
            val c = companies.firstOrNull { it.id == m.companyId } ?: return@mapNotNull null
            val r = role(m.roleKey)
            Workspace(c.id, c.name, c.slug, c.industry, c.color, c.status, r.key, r.name, r.rank,
                m.departmentId, r.perms, c.modules.toSet())
        }.sortedBy { it.name }
    }

    override suspend fun createCompany(name: String, slug: String, industry: Industry): String = lock.withLock {
        delay(500)
        val s = slug.lowercase().trim()
        if (!Regex("^[a-z0-9-]{3,40}$").matches(s)) throw DomainException("Workspace URL: 3–40 lowercase letters, numbers or dashes")
        if (companies.any { it.slug == s }) throw DomainException("That workspace URL is taken")
        val cid = uuid()
        val modules = mutableSetOf(Modules.IDEAS, Modules.OPS_LOG)
        if (industry == Industry.NEWSROOM) modules += Modules.COVERAGE
        if (industry == Industry.NEWSROOM || industry == Industry.AGENCY) modules += Modules.PUBLISHING
        val palette = listOf(0xFF1D4ED8, 0xFF0F766E, 0xFF7C3AED, 0xFFB45309, 0xFFBE123C)
        companies += Co(cid, name.trim(), s, industry, "active", palette[companies.size % palette.size], modules, now())
        val depts = when (industry) {
            Industry.NEWSROOM -> listOf("Reporting", "Editorial", "Video", "Creative", "Social Media", "Website")
            Industry.AGENCY -> listOf("Accounts", "Creative", "Performance", "Content", "Social Media")
            Industry.SOFTWARE -> listOf("Engineering", "Product", "Design", "QA", "Operations")
            Industry.GENERAL -> listOf("Operations", "Sales", "Support")
        }
        depts.forEach { departments += Department(uuid(), cid, it) }
        members += Mem(uuid(), cid, uid(), "owner", null, "Owner", emptyList())
        channels += Ch(uuid(), cid, "general", "channel")
        channels += Ch(uuid(), cid, "announcements", "announcement")
        cid
    }

    override suspend fun acceptInvitation(token: String): String = lock.withLock {
        delay(400)
        val inv = invitations.firstOrNull { it.token.equals(token.trim(), true) && !it.used }
            ?: throw DomainException("Invalid or already used invitation code")
        val email = me?.email ?: throw DomainException("Please sign in")
        if (!inv.email.equals(email, true)) throw DomainException("This invitation was sent to a different email")
        inv.used = true
        if (membership(inv.companyId) == null)
            members += Mem(uuid(), inv.companyId, uid(), inv.roleKey, inv.departmentId, "", emptyList())
        inv.companyId
    }

    override suspend fun createInvitation(companyId: String, email: String, roleKey: String, departmentId: String?): String =
        lock.withLock {
            if (!can(companyId, Perm.MEMBER_INVITE)) throw DomainException("You can't invite people here")
            if (role(roleKey).rank >= rank(companyId) && rank(companyId) < 100)
                throw DomainException("You can only invite below your own role")
            val token = uuid().replace("-", "").take(10).uppercase()
            invitations += Inv(token, companyId, email.trim().lowercase(), roleKey, departmentId)
            token
        }

    override suspend fun setModule(companyId: String, module: String, enabled: Boolean) {
        lock.withLock {
        if (!can(companyId, Perm.MODULE_MANAGE)) throw DomainException("Only admins can change modules")
        val c = companies.first { it.id == companyId }
        if (enabled) c.modules += module else c.modules -= module
            Unit
        }
    }

    // ---------------------------------------------------------------- team
    override suspend fun members(companyId: String): List<Member> = lock.withLock {
        if (!isMember(companyId)) return@withLock emptyList()
        members.filter { it.companyId == companyId }.map { m ->
            val u = users.first { it.id == m.userId }; val r = role(m.roleKey)
            Member(u.id, m.id, companyId, u.name, u.email, r.key, r.name, r.rank, m.departmentId, m.title, m.skills,
                m.availability, tasks.count { it.companyId == companyId && it.assignedTo == u.id && it.status.isActive })
        }.sortedByDescending { it.roleRank }
    }

    override suspend fun departments(companyId: String): List<Department> = lock.withLock {
        if (!isMember(companyId)) emptyList() else departments.filter { it.companyId == companyId }
    }

    // ---------------------------------------------------------------- tasks
    override suspend fun tasks(companyId: String, updatedAfter: Long?): List<Task> = lock.withLock {
        delay(250)
        tasks.filter { it.companyId == companyId && canView(it) && (updatedAfter == null || it.updatedAt > updatedAfter) }
    }

    override suspend fun createTask(draft: TaskDraft): Task = lock.withLock {
        val cid = draft.companyId
        val full = can(cid, Perm.TASK_CREATE)
        if (!full && !can(cid, Perm.TASK_REQUEST)) throw DomainException("You can't create tasks here")
        val status = when {
            !full -> TaskStatus.INBOX
            draft.openPool -> TaskStatus.OPEN
            draft.assignedTo != null -> TaskStatus.ASSIGNED
            else -> TaskStatus.INBOX
        }
        val t = Task(uuid(), cid, draft.parentTaskId, draft.departmentId, draft.title.trim(), draft.description.trim(),
            status, draft.priority, uid(), if (full) draft.assignedTo else null, full && draft.openPool, null,
            draft.dueAt, null, draft.approvalChain, 0, null, 1, now(), now())
        tasks += t
        draft.checklist.filter { it.isNotBlank() }.forEachIndexed { i, l -> checklists += ChecklistItem(uuid(), t.id, l, false, i) }
        log(t, "created")
        notify(cid, t.assignedTo, "assignment", "New task assigned", t.title, t.id)
        bus.tryEmit("tasks:$cid")
        t
    }

    override suspend fun claimTask(taskId: String): ClaimResult = lock.withLock {
        delay(300) // network
        val t = tasks.firstOrNull { it.id == taskId } ?: return@withLock ClaimResult.FORBIDDEN
        if (!can(t.companyId, Perm.TASK_CLAIM)) return@withLock ClaimResult.FORBIDDEN
        val m = membership(t.companyId)!!
        if (t.departmentId != null && m.departmentId != t.departmentId && !can(t.companyId, Perm.TASK_VIEW_ALL))
            return@withLock ClaimResult.NOT_ELIGIBLE
        // the conditional update — exactly one winner
        if (t.status != TaskStatus.OPEN || !t.isOpenPool || t.assignedTo != null) return@withLock ClaimResult.ALREADY_CLAIMED
        val n = bump(t) { it.copy(assignedTo = uid(), status = TaskStatus.ASSIGNED, isOpenPool = false) }
        log(n, "claimed")
        notify(t.companyId, t.createdBy, "claim", "Task claimed", "${nameOf(uid())} claimed “${t.title}”", t.id)
        ClaimResult.SUCCESS
    }

    override suspend fun releaseTask(taskId: String, reason: String) {
        lock.withLock {
        if (reason.isBlank()) throw DomainException("Please give a reason")
        val t = tasks[taskIndex(taskId)]
        if (t.assignedTo != uid() && !can(t.companyId, Perm.TASK_ASSIGN)) throw DomainException("Not allowed")
        val n = bump(t) { it.copy(assignedTo = null, status = TaskStatus.OPEN, isOpenPool = true) }
        log(n, "released", reason)
        notify(t.companyId, t.createdBy, "release", "Task returned to pool", t.title, t.id)
            Unit
        }
    }

    override suspend fun setTaskStatus(taskId: String, status: TaskStatus, expectedVersion: Int, note: String?): Int = lock.withLock {
        delay(200)
        val t = tasks[taskIndex(taskId)]
        if (!isMember(t.companyId)) throw DomainException("Not allowed")
        if (t.version != expectedVersion) throw VersionConflictException()
        if (status == TaskStatus.APPROVED || status == TaskStatus.CHANGES_REQUESTED) throw DomainException("Use the review actions")
        val manager = can(t.companyId, Perm.TASK_ASSIGN)
        if (status == TaskStatus.PUBLISHED && !(t.status == TaskStatus.APPROVED && manager))
            throw DomainException("Only approved work can be published by a manager")
        if (!manager) {
            if (t.assignedTo != uid()) throw DomainException("Only the assignee can update this task")
            val allowed = setOf(
                TaskStatus.ASSIGNED to TaskStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS to TaskStatus.BLOCKED,
                TaskStatus.BLOCKED to TaskStatus.IN_PROGRESS, TaskStatus.CHANGES_REQUESTED to TaskStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS to TaskStatus.SUBMITTED, TaskStatus.CHANGES_REQUESTED to TaskStatus.SUBMITTED)
            if ((t.status to status) !in allowed) throw DomainException("Can't move from ${t.status.label} to ${status.label}")
        }
        val n = bump(t) {
            if (status == TaskStatus.OPEN) it.copy(status = status, isOpenPool = true, assignedTo = null)
            else it.copy(status = status, blockedReason = if (status == TaskStatus.BLOCKED) note else null,
                approvalStage = if (status == TaskStatus.SUBMITTED) 0 else it.approvalStage)
        }
        log(n, "status", "${t.status.label} → ${status.label}" + (note?.let { ": $it" } ?: ""))
        if (status == TaskStatus.SUBMITTED) {
            val first = t.approvalChain.firstOrNull()
            members.filter { it.companyId == t.companyId && it.roleKey == first && it.userId != uid() &&
                (t.departmentId == null || it.departmentId == t.departmentId) }
                .forEach { notify(t.companyId, it.userId, "review", "Work submitted for review", t.title, t.id) }
        }
        n.version
    }

    override suspend fun decideApproval(taskId: String, approve: Boolean, note: String?): String = lock.withLock {
        delay(250)
        val t = tasks[taskIndex(taskId)]
        if (!can(t.companyId, Perm.TASK_APPROVE)) throw DomainException("You can't approve work")
        if (t.status != TaskStatus.SUBMITTED) throw DomainException("This task is not awaiting review")
        if (t.assignedTo == uid()) throw DomainException("You cannot approve your own work")
        if (!approve && note.isNullOrBlank()) throw DomainException("Explain what needs to change")
        val stageRole = t.approvalChain.getOrNull(t.approvalStage) ?: "admin"
        val needed = roles.firstOrNull { it.key == stageRole }?.rank ?: 100
        if (rank(t.companyId) < needed) throw DomainException("This stage needs a ${role(stageRole).name} or higher")
        if (!approve) {
            val n = bump(t) { it.copy(status = TaskStatus.CHANGES_REQUESTED, approvalStage = 0) }
            log(n, "changes_requested", note.orEmpty())
            notify(t.companyId, t.assignedTo, "changes", "Changes requested", t.title, t.id)
            return@withLock "CHANGES_REQUESTED"
        }
        val last = t.approvalStage + 1 >= t.approvalChain.size.coerceAtLeast(1)
        val n = bump(t) { if (last) it.copy(status = TaskStatus.APPROVED, approvalStage = it.approvalStage + 1)
                          else it.copy(approvalStage = it.approvalStage + 1) }
        log(n, "approved", "Stage ${t.approvalStage + 1} · ${role(stageRole).name}" + if (last) " · final" else "")
        if (last) notify(t.companyId, t.assignedTo, "approved", "Work approved", t.title, t.id)
        if (last) "APPROVED" else "STAGE_APPROVED"
    }

    override suspend fun assignTask(taskId: String, userId: String, expectedVersion: Int): Int = lock.withLock {
        val t = tasks[taskIndex(taskId)]
        if (!can(t.companyId, Perm.TASK_ASSIGN)) throw DomainException("You can't assign tasks")
        if (t.version != expectedVersion) throw VersionConflictException()
        if (membership(t.companyId, userId) == null) throw DomainException("That person isn't in this company")
        val n = bump(t) { it.copy(assignedTo = userId, isOpenPool = false,
            status = if (it.status in setOf(TaskStatus.INBOX, TaskStatus.EVALUATION, TaskStatus.OPEN)) TaskStatus.ASSIGNED else it.status) }
        log(n, if (t.assignedTo == null) "assigned" else "reassigned", "to ${nameOf(userId)}")
        notify(t.companyId, userId, "assignment", "New task assigned", t.title, t.id)
        n.version
    }

    override suspend fun checklist(taskId: String) = lock.withLock { checklists.filter { it.taskId == taskId }.sortedBy { it.position } }

    override suspend fun toggleChecklist(itemId: String, done: Boolean) {
        lock.withLock {
        val i = checklists.indexOfFirst { it.id == itemId }
        if (i < 0) return@withLock
        val t = tasks.first { it.id == checklists[i].taskId }
        if (t.assignedTo != uid() && !can(t.companyId, Perm.TASK_EDIT)) throw DomainException("Only the assignee can tick items")
        checklists[i] = checklists[i].copy(done = done)
            Unit
        }
    }

    override suspend fun activity(taskId: String) = lock.withLock { activity.filter { it.taskId == taskId }.sortedByDescending { it.at } }
    override suspend fun comments(taskId: String) = lock.withLock { commentsList.filter { it.taskId == taskId }.sortedBy { it.at } }
    override suspend fun addComment(id: String, companyId: String, taskId: String, body: String) {
        lock.withLock {
        if (commentsList.none { it.id == id }) commentsList += Comment(id, taskId, uid(), nameOf(uid()), body.trim(), now())
            Unit
        }
    }

    override suspend fun dashboard(companyId: String): DashboardStats = lock.withLock {
        val all = can(companyId, Perm.REPORT_COMPANY)
        val u = uid()
        val scope = tasks.filter { it.companyId == companyId && it.parentTaskId == null &&
            (all || it.assignedTo == u || it.createdBy == u) }
        val dayStart = now() - (now() % 86_400_000)
        DashboardStats(
            scope = if (all) "company" else "personal",
            active = scope.count { it.status.isActive },
            overdue = scope.count { it.isOverdue },
            awaitingReview = scope.count { it.status == TaskStatus.SUBMITTED },
            blocked = scope.count { it.status == TaskStatus.BLOCKED },
            openPool = tasks.count { it.companyId == companyId && it.isOpenPool && it.status == TaskStatus.OPEN && canView(it) },
            doneToday = scope.count { it.status.isDone && it.updatedAt >= dayStart },
            doneWeek = scope.count { it.status.isDone && it.updatedAt >= now() - h(24.0 * 7) },
        )
    }

    // ---------------------------------------------------------------- chat
    override suspend fun channels(companyId: String): List<Channel> = lock.withLock {
        if (!isMember(companyId)) return@withLock emptyList()
        channels.filter { it.companyId == companyId }.map { c ->
            val last = messagesList.filter { it.channelId == c.id }.maxByOrNull { it.at }
            Channel(c.id, companyId, c.name, c.kind, 0, last?.let { "${it.senderName.substringBefore(' ')}: ${it.body}" }, last?.at)
        }.sortedByDescending { it.lastAt ?: 0 }
    }

    override suspend fun messages(channelId: String, olderThan: Long?, limit: Int): List<Message> = lock.withLock {
        val c = channels.firstOrNull { it.id == channelId } ?: return@withLock emptyList()
        if (!isMember(c.companyId)) return@withLock emptyList()
        messagesList.filter { it.channelId == channelId && (olderThan == null || it.at < olderThan) }
            .sortedByDescending { it.at }.take(limit)
    }

    override suspend fun sendMessage(id: String, companyId: String, channelId: String, body: String) {
        lock.withLock {
            val c = channels.firstOrNull { it.id == channelId && it.companyId == companyId } ?: throw DomainException("Channel not found")
            if (!isMember(companyId)) throw DomainException("Not a member")
            if (c.kind == "announcement" && !can(companyId, Perm.CHAT_ANNOUNCE)) throw DomainException("Only admins can post announcements")
            if (messagesList.any { it.id == id }) return@withLock // idempotent retry
            messagesList += Message(id, companyId, channelId, uid(), nameOf(uid()), body.trim(), now())
            bus.tryEmit("messages:$channelId")
        }
        // a teammate replies, so the demo feels live
        val count = lock.withLock { messagesList.count { it.channelId == channelId && it.senderId == me?.id } }
        if (count % 2 == 1) scope.launch {
            delay(2200)
            lock.withLock {
                val other = members.filter { it.companyId == companyId && it.userId != me?.id }.randomOrNull() ?: return@withLock
                val replies = listOf("On it.", "Thanks, noted.", "Can we sync on this after lunch?", "Great — I'll update the task.", "Seen. Will share by EOD.")
                messagesList += Message(uuid(), companyId, channelId, other.userId, nameOf(other.userId), replies.random(), now())
            }
            bus.tryEmit("messages:$channelId")
        }
    }

    override fun changes(companyId: String, channelId: String?): Flow<String> =
        bus.filter { it == "tasks:$companyId" || it == "notifications" || (channelId != null && it == "messages:$channelId") }

    // ---------------------------------------------------------------- notifications
    override suspend fun notifications(companyId: String): List<AppNotification> = lock.withLock {
        val u = uid()
        notes.filter { it.companyId == companyId && it.id.startsWith("$u|") }.sortedByDescending { it.at }
    }

    override suspend fun markNotificationRead(id: String) {
        lock.withLock {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = notes[i].copy(read = true)
            Unit
        }
    }

    // ---------------------------------------------------------------- coverage
    override suspend fun coverageEvents(companyId: String): List<CoverageEvent> = lock.withLock {
        if (!isMember(companyId) || companies.first { it.id == companyId }.modules.contains(Modules.COVERAGE).not()) emptyList()
        else events.filter { it.companyId == companyId }
            .map { e -> e.copy(submissions = submissionsList.count { it.eventId == e.id }) }
            .sortedByDescending { it.eventDate }
    }

    override suspend fun coverageSubmissions(eventId: String) = lock.withLock {
        submissionsList.filter { it.eventId == eventId }.sortedByDescending { it.editorialScore() }
    }

    override suspend fun createCoverageEvent(companyId: String, name: String, sport: String, venue: String, date: Long, importance: Priority) {
        lock.withLock {
            if (!can(companyId, Perm.COVERAGE_MANAGE)) throw DomainException("Only editors can register events")
            events += CoverageEvent(uuid(), companyId, name.trim(), sport.trim(), venue.trim(), date, importance, "planned")
            Unit
        }
    }

    override suspend fun submitCoverage(companyId: String, eventId: String, athlete: String, result: String, quotes: String, angle: String, links: List<String>) {
        lock.withLock {
            if (!can(companyId, Perm.COVERAGE_SUBMIT)) throw DomainException("You can't submit coverage")
            submissionsList += CoverageSubmission(uuid(), eventId, nameOf(uid()), athlete, result, quotes, angle, links, false,
                mapOf('F' to 5, 'I' to 3, 'U' to 3, 'A' to if (links.isEmpty()) 1 else 4, 'R' to 3, 'V' to 2), "submitted", now())
            Unit
        }
    }

    override suspend fun setEditorialStatus(submissionId: String, status: String) {
        lock.withLock {
        val i = submissionsList.indexOfFirst { it.id == submissionId }
        if (i >= 0) submissionsList[i] = submissionsList[i].copy(editorialStatus = status)
            Unit
        }
    }

    // ---------------------------------------------------------------- platform
    override suspend fun platformOverview(): List<CompanyOverview> = lock.withLock {
        if (me?.platform != true) throw DomainException("Platform owners only")
        companies.map { c -> CompanyOverview(c.id, c.name, c.industry.label, c.status, "free",
            members.count { it.companyId == c.id }, tasks.count { it.companyId == c.id }, c.createdAt) }
    }

    override suspend fun platformSetStatus(companyId: String, status: String) {
        lock.withLock {
        if (me?.platform != true) throw DomainException("Platform owners only")
        companies.first { it.id == companyId }.status = status
            Unit
        }
    }

    // ---------------------------------------------------------------- seed
    private fun seed() {
        val dev = U("u-dev", "owner@devnex.app", "Devanshu", true)
        val priya = U("u-priya", "head@devnex.app", "Priya Nair", false)
        val arjun = U("u-arjun", "lead@devnex.app", "Arjun Mehta", false)
        val kabir = U("u-kabir", "reporter@devnex.app", "Kabir Shah", false)
        val neha = U("u-neha", "editor@devnex.app", "Neha Iyer", false)
        val zoya = U("u-zoya", "zoya@devnex.app", "Zoya Khan", false)
        val rohan = U("u-rohan", "rohan@devnex.app", "Rohan Das", false)
        users += listOf(dev, priya, arjun, kabir, neha, zoya, rohan)

        val news = Co("c-news", "Velocity Sports Media", "velocity-sports", Industry.NEWSROOM, "active", 0xFF1D4ED8,
            mutableSetOf(Modules.COVERAGE, Modules.PUBLISHING, Modules.IDEAS, Modules.OPS_LOG), now() - h(24.0 * 40))
        val agency = Co("c-agency", "Pixel Orbit Agency", "pixel-orbit", Industry.AGENCY, "active", 0xFF0F766E,
            mutableSetOf(Modules.PUBLISHING, Modules.IDEAS), now() - h(24.0 * 12))
        companies += listOf(news, agency)

        val d = listOf("Reporting", "Editorial", "Video", "Creative", "Social Media", "Website")
            .associateWith { Department("d-${it.lowercase().replace(' ', '-')}", news.id, it) }
        departments += d.values
        listOf("Accounts", "Creative", "Performance").forEach { departments += Department("da-${it.lowercase()}", agency.id, it) }

        members += Mem("m1", news.id, dev.id, "owner", null, "Founder", listOf("management"))
        members += Mem("m2", news.id, priya.id, "head", d["Editorial"]!!.id, "Editorial Head", listOf("editing"))
        members += Mem("m3", news.id, arjun.id, "lead", d["Reporting"]!!.id, "Reporting Lead", listOf("reporting"))
        members += Mem("m4", news.id, kabir.id, "member", d["Reporting"]!!.id, "Reporter", listOf("reporting", "athletics"))
        members += Mem("m5", news.id, neha.id, "member", d["Video"]!!.id, "Video Editor", listOf("video", "graphics"))
        members += Mem("m6", news.id, zoya.id, "member", d["Social Media"]!!.id, "Social Media Manager", listOf("social"), "busy")
        members += Mem("m7", news.id, rohan.id, "member", d["Website"]!!.id, "Website Manager", listOf("web", "seo"))
        members += Mem("m8", agency.id, dev.id, "admin", null, "Tech Partner", emptyList())
        members += Mem("m9", agency.id, priya.id, "owner", null, "Founder", emptyList())

        fun task(id: String, title: String, st: TaskStatus, pr: Priority, dept: String?, by: String, to: String?,
                 dueH: Double?, parent: String? = null, pool: Boolean = false, desc: String = "", stage: Int = 0,
                 cid: String = news.id, ageH: Double = 30.0) =
            Task(id, cid, parent, dept?.let { d[it]?.id ?: "da-${it.lowercase()}" }, title, desc, st, pr, by, to, pool, null,
                dueH?.let { now() + h(it) }, null, listOf("lead", "head"), stage, null, 1, now() - h(ageH / 3), now() - h(ageH))

        val parent = task("t-athletics", "Cover National Athletics Championship", TaskStatus.IN_PROGRESS, Priority.HIGH,
            "Editorial", dev.id, priya.id, 30.0, desc = "End-to-end coverage: results, interviews, video, reels, graphics, web story and social. Media stays in the shared drive — link it, don't upload.")
        tasks += parent
        tasks += task("t-a1", "Event coverage — results & interviews", TaskStatus.APPROVED, Priority.HIGH, "Reporting", priya.id, kabir.id, -2.0, parent.id, stage = 2)
        tasks += task("t-a2", "Edit main event video", TaskStatus.IN_PROGRESS, Priority.HIGH, "Video", priya.id, neha.id, 8.0, parent.id)
        tasks += task("t-a3", "Three short-form reels", TaskStatus.OPEN, Priority.NORMAL, "Video", priya.id, null, 20.0, parent.id, pool = true)
        tasks += task("t-a4", "Thumbnails & quote graphics", TaskStatus.OPEN, Priority.NORMAL, "Creative", priya.id, null, 18.0, parent.id, pool = true)
        tasks += task("t-a5", "Captions & publishing slots", TaskStatus.BLOCKED, Priority.NORMAL, "Social Media", priya.id, zoya.id, 26.0, parent.id)
            .copy(blockedReason = "Waiting for final reels")
        tasks += task("t-a6", "Website story & SEO", TaskStatus.SUBMITTED, Priority.NORMAL, "Website", priya.id, rohan.id, 12.0, parent.id)
        tasks += task("t-2", "Interview: 400m national record holder", TaskStatus.SUBMITTED, Priority.CRITICAL, "Reporting", arjun.id, kabir.id, 4.0,
            desc = "15-min sit-down after the final. Quotes + 3 social cut-downs.", ageH = 10.0)
        tasks += task("t-3", "Verify relay team timings with federation", TaskStatus.ASSIGNED, Priority.HIGH, "Reporting", arjun.id, kabir.id, -3.0, ageH = 48.0)
        tasks += task("t-4", "District meet — live updates thread", TaskStatus.OPEN, Priority.NORMAL, "Reporting", arjun.id, null, 36.0, pool = true)
        tasks += task("t-5", "Weekly newsletter — top 5 stories", TaskStatus.CHANGES_REQUESTED, Priority.LOW, "Editorial", priya.id, kabir.id, 50.0)
        tasks += task("t-6", "Podcast segment: coach roundtable", TaskStatus.INBOX, Priority.NORMAL, "Editorial", kabir.id, null, null)
        tasks += task("t-7", "Highlights package — junior nationals", TaskStatus.PUBLISHED, Priority.NORMAL, "Video", priya.id, neha.id, -30.0, stage = 2, ageH = 5.0)
        tasks += task("t-8", "Q4 campaign moodboard", TaskStatus.IN_PROGRESS, Priority.HIGH, "Creative", priya.id, dev.id, 40.0, cid = agency.id)
        tasks += task("t-9", "Client report — October performance", TaskStatus.OPEN, Priority.NORMAL, "Performance", priya.id, null, 70.0, pool = true, cid = agency.id)

        listOf("Collect official results sheet", "Record 2 athlete interviews", "Note federation quotes", "Link photos folder").forEachIndexed { i, l ->
            checklists += ChecklistItem("cl-a1-$i", "t-a1", l, true, i) }
        listOf("Rough cut", "Colour & sound pass", "Lower thirds", "Export 1080p & 9:16").forEachIndexed { i, l ->
            checklists += ChecklistItem("cl-a2-$i", "t-a2", l, i < 2, i) }
        listOf("Prep questions", "Book slot with athlete", "Record", "Transcribe key quotes").forEachIndexed { i, l ->
            checklists += ChecklistItem("cl-2-$i", "t-2", l, true, i) }

        tasks.filter { it.companyId == news.id }.forEach { t ->
            activity += TaskActivity(uuid(), t.id, nameOf(t.createdBy), "created", "", t.createdAt)
            if (t.assignedTo != null) activity += TaskActivity(uuid(), t.id, nameOf(t.createdBy), "assigned", "to ${nameOf(t.assignedTo)}", t.createdAt + h(1.0))
        }
        commentsList += Comment("cm1", "t-a2", priya.id, "Priya Nair", "Keep the opening under 8 seconds — start on the photo finish.", now() - h(3.0))
        commentsList += Comment("cm2", "t-a2", neha.id, "Neha Iyer", "Done. Pulling the slow-mo from camera B now.", now() - h(2.5))

        val general = Ch("ch-general", news.id, "general", "channel")
        val ann = Ch("ch-ann", news.id, "announcements", "announcement")
        val rep = Ch("ch-reporting", news.id, "reporting", "channel")
        val vid = Ch("ch-video", news.id, "video-editing", "channel")
        val soc = Ch("ch-social", news.id, "social-media", "channel")
        channels += listOf(general, ann, rep, vid, soc, Ch("ch-ag-general", agency.id, "general", "channel"))
        fun msg(ch: Ch, u: U, body: String, agoMin: Int) =
            Message(uuid(), ch.companyId, ch.id, u.id, u.name, body, now() - agoMin * 60_000L)
        messagesList += listOf(
            msg(ann, dev, "Welcome to DevNex. All coverage, edits and approvals now live here. No files — link to the shared drive.", 600),
            msg(general, priya, "Championship day! Results desk opens 9:30.", 240),
            msg(general, arjun, "Kabir is at the stadium. First updates by 11.", 230),
            msg(rep, kabir, "400m final done — national record! Interview locked for 2pm.", 90),
            msg(rep, arjun, "Brilliant. Submit the interview task once the quotes are in.", 85),
            msg(vid, neha, "Main video rough cut is ready for a look.", 60),
            msg(soc, zoya, "Holding the reel slots until video lands.", 30),
        )

        notes += AppNotification("u-dev|n1", news.id, "review", "Work submitted for review", "Website story & SEO", "t-a6", false, now() - h(1.0))
        notes += AppNotification("u-priya|n2", news.id, "review", "Work submitted for review", "Interview: 400m national record holder", "t-2", false, now() - h(0.5))
        notes += AppNotification("u-kabir|n3", news.id, "changes", "Changes requested", "Weekly newsletter — top 5 stories", "t-5", false, now() - h(4.0))

        val ev = CoverageEvent("e1", news.id, "National Athletics Championship", "Athletics", "Jawaharlal Nehru Stadium", now(), Priority.HIGH, "live")
        events += ev
        events += CoverageEvent("e2", news.id, "Junior Nationals — Day 3", "Athletics", "Patiala", now() - h(24.0 * 6), Priority.NORMAL, "covered")
        events += CoverageEvent("e3", news.id, "State Swimming Trials", "Swimming", "Bengaluru", now() + h(24.0 * 5), Priority.NORMAL, "planned")
        submissionsList += CoverageSubmission("s1", ev.id, "Kabir Shah", "A. Sharma", "400m — 45.12s (NR)", "“I came here for this record.”",
            "National record", listOf("drive.google.com/…/400m-final"), true, mapOf('F' to 5, 'I' to 5, 'U' to 5, 'A' to 4, 'R' to 5, 'V' to 5), "high_priority", now() - h(2.0))
        submissionsList += CoverageSubmission("s2", ev.id, "Kabir Shah", "Relay team", "4x100m — silver", "", "Comeback after injury",
            emptyList(), false, mapOf('F' to 4, 'I' to 3, 'U' to 3, 'A' to 1, 'R' to 3, 'V' to 2), "waiting_info", now() - h(1.0))
    }
}
