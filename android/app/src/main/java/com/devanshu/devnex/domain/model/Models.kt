package com.devanshu.devnex.domain.model

/** Task lifecycle — mirrors the CHECK constraint in the database migration. */
enum class TaskStatus(val key: String, val label: String) {
    INBOX("inbox", "Inbox"),
    EVALUATION("evaluation", "Under evaluation"),
    OPEN("open", "Open"),
    ASSIGNED("assigned", "Assigned"),
    IN_PROGRESS("in_progress", "In progress"),
    BLOCKED("blocked", "Blocked"),
    SUBMITTED("submitted", "In review"),
    CHANGES_REQUESTED("changes_requested", "Changes requested"),
    APPROVED("approved", "Approved"),
    PUBLISHED("published", "Published"),
    CANCELLED("cancelled", "Cancelled");

    val isDone get() = this == APPROVED || this == PUBLISHED
    val isActive get() = this in setOf(ASSIGNED, IN_PROGRESS, BLOCKED, SUBMITTED, CHANGES_REQUESTED)

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: INBOX
        /** The main path shown in the pipeline stepper. */
        val pipeline = listOf(OPEN, ASSIGNED, IN_PROGRESS, SUBMITTED, APPROVED, PUBLISHED)
    }
}

enum class Priority(val key: String, val label: String, val weight: Int) {
    CRITICAL("critical", "Critical", 4), HIGH("high", "High", 3),
    NORMAL("normal", "Normal", 2), LOW("low", "Low", 1);

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: NORMAL }
}

/** Permission keys — identical to the strings seeded by create_company(). */
object Perm {
    const val COMPANY_MANAGE = "company.manage"
    const val MEMBER_INVITE = "member.invite"
    const val MEMBER_MANAGE = "member.manage"
    const val DEPARTMENT_MANAGE = "department.manage"
    const val MODULE_MANAGE = "module.manage"
    const val TASK_CREATE = "task.create"
    const val TASK_REQUEST = "task.request"
    const val TASK_EDIT = "task.edit"
    const val TASK_ASSIGN = "task.assign"
    const val TASK_CLAIM = "task.claim"
    const val TASK_APPROVE = "task.approve"
    const val TASK_VIEW_ALL = "task.view_all"
    const val REPORT_COMPANY = "report.company"
    const val REPORT_DEPARTMENT = "report.department"
    const val AUDIT_VIEW = "audit.view"
    const val CHAT_ANNOUNCE = "chat.announce"
    const val COVERAGE_MANAGE = "coverage.manage"
    const val COVERAGE_SUBMIT = "coverage.submit"
}

object Modules {
    const val COVERAGE = "coverage"
    const val PUBLISHING = "publishing"
    const val IDEAS = "ideas"
    const val OPS_LOG = "ops_log"
}

enum class Industry(val key: String, val label: String, val blurb: String) {
    GENERAL("general", "General business", "Tasks, approvals, chat and reports"),
    NEWSROOM("newsroom", "News & sports media", "Adds Coverage Desk and publishing"),
    AGENCY("agency", "Marketing agency", "Adds publishing calendar"),
    SOFTWARE("software", "Software company", "Engineering-ready departments");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: GENERAL }
}

data class SessionUser(
    val id: String,
    val email: String,
    val fullName: String,
    val isPlatformAdmin: Boolean,
    val needsMfa: Boolean,
)

data class Workspace(
    val companyId: String,
    val name: String,
    val slug: String,
    val industry: Industry,
    val brandColor: Long,
    val status: String,
    val roleKey: String,
    val roleName: String,
    val roleRank: Int,
    val departmentId: String?,
    val permissions: Set<String>,
    val modules: Set<String>,
) {
    fun can(perm: String) = perm in permissions
    fun has(module: String) = module in modules
    val isActive get() = status == "active"
}

data class Department(val id: String, val companyId: String, val name: String)

data class Member(
    val userId: String,
    val membershipId: String,
    val companyId: String,
    val fullName: String,
    val email: String,
    val roleKey: String,
    val roleName: String,
    val roleRank: Int,
    val departmentId: String?,
    val title: String,
    val skills: List<String>,
    val availability: String,
    val activeTasks: Int = 0,
)

data class Task(
    val id: String,
    val companyId: String,
    val parentTaskId: String?,
    val departmentId: String?,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val priority: Priority,
    val createdBy: String,
    val assignedTo: String?,
    val isOpenPool: Boolean,
    val eligibleSkill: String?,
    val dueAt: Long?,
    val estimatedEffort: Double?,
    val approvalChain: List<String>,
    val approvalStage: Int,
    val blockedReason: String?,
    val version: Int,
    val updatedAt: Long,
    val createdAt: Long,
) {
    val isOverdue get() = dueAt != null && dueAt < System.currentTimeMillis() && !status.isDone && status != TaskStatus.CANCELLED
    val currentStageRole get() = approvalChain.getOrNull(approvalStage)
}

data class TaskDraft(
    val companyId: String,
    val title: String,
    val description: String,
    val priority: Priority,
    val departmentId: String?,
    val assignedTo: String?,
    val openPool: Boolean,
    val dueAt: Long?,
    val parentTaskId: String? = null,
    val checklist: List<String> = emptyList(),
    val approvalChain: List<String> = listOf("lead", "head"),
)

data class ChecklistItem(val id: String, val taskId: String, val label: String, val done: Boolean, val position: Int)

data class TaskActivity(val id: String, val taskId: String, val actorName: String, val action: String, val detail: String, val at: Long)

data class Comment(val id: String, val taskId: String, val authorId: String, val authorName: String, val body: String, val at: Long)

data class Channel(
    val id: String,
    val companyId: String,
    val name: String,
    val kind: String,
    val unread: Int = 0,
    val lastMessage: String? = null,
    val lastAt: Long? = null,
)

data class Message(
    val id: String,
    val companyId: String,
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val at: Long,
    val pending: Boolean = false,
)

data class AppNotification(
    val id: String,
    val companyId: String,
    val kind: String,
    val title: String,
    val body: String,
    val taskId: String?,
    val read: Boolean,
    val at: Long,
)

data class DashboardStats(
    val scope: String = "personal",
    val active: Int = 0,
    val overdue: Int = 0,
    val awaitingReview: Int = 0,
    val blocked: Int = 0,
    val openPool: Int = 0,
    val doneToday: Int = 0,
    val doneWeek: Int = 0,
)

data class CoverageEvent(
    val id: String, val companyId: String, val name: String, val sport: String, val venue: String,
    val eventDate: Long, val importance: Priority, val status: String, val submissions: Int = 0,
)

data class CoverageSubmission(
    val id: String, val eventId: String, val reporterName: String, val athlete: String, val result: String,
    val quotes: String, val newsAngle: String, val mediaLinks: List<String>, val verified: Boolean,
    val scores: Map<Char, Int>, val editorialStatus: String, val at: Long,
) {
    /** P = 0.25F + 0.25I + 0.20U + 0.15A + 0.10R + 0.05V (weights configurable per company). */
    fun editorialScore(weights: Map<Char, Double> = DEFAULT_WEIGHTS): Double =
        weights.entries.sumOf { (k, w) -> (scores[k] ?: 0) * w }

    companion object {
        val DEFAULT_WEIGHTS = mapOf('F' to .25, 'I' to .25, 'U' to .20, 'A' to .15, 'R' to .10, 'V' to .05)
    }
}

data class CompanyOverview(
    val companyId: String, val name: String, val industry: String, val status: String,
    val plan: String, val members: Int, val tasks: Int, val createdAt: Long,
)

enum class ClaimResult { SUCCESS, ALREADY_CLAIMED, NOT_ELIGIBLE, FORBIDDEN;
    companion object {
        fun of(s: String) = when (s) {
            "CLAIM_SUCCESS" -> SUCCESS; "CLAIM_FAILED" -> ALREADY_CLAIMED
            "NOT_ELIGIBLE" -> NOT_ELIGIBLE; else -> FORBIDDEN
        }
    }
}

/** Assignment suggestion: S = 0.40K + 0.25C + 0.20D + 0.15T (manager makes the final call). */
data class AssigneeSuggestion(val member: Member, val score: Double, val reason: String)

class VersionConflictException : Exception("Someone else changed this task. Refresh and try again.")
class DomainException(message: String) : Exception(message)
