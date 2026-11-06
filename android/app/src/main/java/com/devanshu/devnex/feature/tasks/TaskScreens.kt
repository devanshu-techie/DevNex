package com.devanshu.devnex.feature.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.*
import com.devanshu.devnex.feature.dashboard.MainNav
import com.devanshu.devnex.feature.dashboard.MainViewModel
import com.devanshu.devnex.feature.dashboard.WorkspaceHeader
import com.devanshu.devnex.feature.dashboard.rankOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================== shared card
@Composable
fun TaskCard(t: Task, vm: MainViewModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val all by vm.tasks.collectAsStateWithLifecycle()
    val subs = remember(all, t.id) { all.filter { it.parentTaskId == t.id } }
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, accent = if (t.isOverdue) Nx.Red else null, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PriorityBar(t.priority)
            Spacer(Modifier.width(10.dp))
            Text(vm.departmentName(t.departmentId)?.uppercase() ?: "GENERAL", style = MaterialTheme.typography.labelSmall,
                color = LocalNx.current.mute, modifier = Modifier.weight(1f))
            StatusChip(t.status)
        }
        Spacer(Modifier.height(8.dp))
        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subs.isNotEmpty()) {
            val done = subs.count { it.status.isDone }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { done.toFloat() / subs.size }, Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = Nx.Cyan, trackColor = LocalNx.current.line)
                Spacer(Modifier.width(8.dp))
                Text("$done/${subs.size}", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val who = vm.memberName(t.assignedTo)
            if (who != null) { Avatar(who, 22.dp); Spacer(Modifier.width(6.dp)); Text(who.substringBefore(' '), style = MaterialTheme.typography.bodySmall) }
            else if (t.isOpenPool) { Icon(Icons.Rounded.Waves, null, Modifier.size(16.dp), tint = Nx.Cyan); Spacer(Modifier.width(6.dp))
                Text("Open pool", style = MaterialTheme.typography.bodySmall, color = Nx.Cyan) }
            else Text("Unassigned", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            Spacer(Modifier.weight(1f))
            if (t.dueAt != null) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = if (t.isOverdue) Nx.Red else LocalNx.current.mute)
                Spacer(Modifier.width(4.dp))
                Text(if (t.isOverdue) "Overdue ${relativeTime(t.dueAt)}" else relativeTime(t.dueAt), style = MaterialTheme.typography.labelSmall,
                    color = if (t.isOverdue) Nx.Red else LocalNx.current.mute)
            }
        }
    }
}

// ============================================================== My Work tab
private enum class WorkFilter(val label: String) { MINE("Mine"), POOL("Open pool"), REVIEW("To review"), ALL("All") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkTab(vm: MainViewModel, nav: MainNav) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val w by vm.workspace.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(WorkFilter.MINE) }
    var query by rememberSaveable { mutableStateOf("") }
    val me = user?.id
    val filters = WorkFilter.entries.filter {
        when (it) { WorkFilter.REVIEW -> w?.can(Perm.TASK_APPROVE) == true; WorkFilter.ALL -> w?.can(Perm.TASK_VIEW_ALL) == true; else -> true }
    }
    val shown = remember(tasks, filter, query, me) {
        tasks.filter { t ->
            when (filter) {
                WorkFilter.MINE -> t.assignedTo == me && t.status != TaskStatus.CANCELLED
                WorkFilter.POOL -> t.isOpenPool && t.status == TaskStatus.OPEN
                WorkFilter.REVIEW -> t.status == TaskStatus.SUBMITTED && t.assignedTo != me
                WorkFilter.ALL -> t.parentTaskId == null
            } && (query.isBlank() || t.title.contains(query, true))
        }.sortedWith(compareBy<Task> { it.status.isDone }.thenByDescending { it.priority.weight }.thenBy { it.dueAt ?: Long.MAX_VALUE })
    }
    val canCreate = w?.can(Perm.TASK_CREATE) == true || w?.can(Perm.TASK_REQUEST) == true

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { WorkspaceHeader(vm, nav, "My Work") }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search tasks") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { f ->
                        val n = tasks.count { t -> when (f) {
                            WorkFilter.MINE -> t.assignedTo == me && !t.status.isDone && t.status != TaskStatus.CANCELLED
                            WorkFilter.POOL -> t.isOpenPool && t.status == TaskStatus.OPEN
                            WorkFilter.REVIEW -> t.status == TaskStatus.SUBMITTED && t.assignedTo != me
                            WorkFilter.ALL -> t.parentTaskId == null } }
                        FilterChip(filter == f, { filter = f }, label = { Text("${f.label}  $n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nx.Violet.copy(alpha = .25f), selectedLabelColor = Nx.Ink))
                    }
                }
            }
            if (vm.refreshing && tasks.isEmpty()) item { SkeletonList() }
            else if (shown.isEmpty()) item {
                EmptyState(
                    when (filter) { WorkFilter.POOL -> Icons.Rounded.Waves; WorkFilter.REVIEW -> Icons.Rounded.AssignmentTurnedIn; else -> Icons.Rounded.Inbox },
                    when (filter) { WorkFilter.POOL -> "The pool is empty"; WorkFilter.REVIEW -> "Nothing to review"; else -> "No tasks here" },
                    when (filter) { WorkFilter.POOL -> "When managers open work to everyone eligible, it shows up here — first valid claim wins."
                        WorkFilter.REVIEW -> "Submitted work waiting for your approval appears here."
                        else -> "Claim something from the Open pool, or create a task." },
                )
            }
            items(shown, key = { it.id }) { t ->
                TaskCard(t, vm, Modifier.padding(horizontal = 16.dp, vertical = 5.dp).animateItem()) { nav.openTask(t.id) }
            }
        }
        if (canCreate) {
            ExtendedFloatingActionButton(
                onClick = { nav.createTask(null) }, containerColor = Nx.Violet, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (w?.can(Perm.TASK_CREATE) == true) "New task" else "Request task") },
            )
        }
    }
}

/** Shown in place of Coverage for companies without the newsroom module. */
@Composable
fun ProjectsTab(vm: MainViewModel, nav: MainNav) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val parents = tasks.filter { p -> p.parentTaskId == null && tasks.any { it.parentTaskId == p.id } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { WorkspaceHeader(vm, nav, "Projects") }
        if (parents.isEmpty()) item {
            EmptyState(Icons.Rounded.AccountTree, "No projects yet", "Break a big task into subtasks and it becomes a project with live progress.")
        }
        items(parents, key = { it.id }) { p ->
            val subs = tasks.filter { it.parentTaskId == p.id }
            GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = { nav.openTask(p.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseRing(subs.count { it.status.isDone }.toFloat() / subs.size, Modifier.size(64.dp), stroke = 6.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text("${subs.size} deliverables · ${subs.count { it.status == TaskStatus.BLOCKED }} blocked",
                            style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                }
            }
        }
    }
}

// ============================================================== Task detail
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: TaskRepository,
    private val team: TeamRepository,
    private val workspaces: WorkspaceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val taskId: String = checkNotNull(saved["taskId"])
    val me = auth.session.value?.id
    val workspace = workspaces.active
    val task = repo.observeTask(taskId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val checklist = repo.observeChecklist(taskId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subtasks = workspace.flatMapLatest { w -> if (w == null) flowOf(emptyList()) else repo.observeTasks(w.companyId) }
        .map { l -> l.filter { it.parentTaskId == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val allTasks = workspace.flatMapLatest { w -> if (w == null) flowOf(emptyList()) else repo.observeTasks(w.companyId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var activity by mutableStateOf<List<TaskActivity>>(emptyList()); private set
    var comments by mutableStateOf<List<Comment>>(emptyList()); private set
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var celebrate by mutableStateOf(0); private set

    init { reloadSide() }

    private fun reloadSide() = viewModelScope.launch {
        runCatching { activity = repo.activity(taskId) }
        runCatching { comments = repo.comments(taskId) }
        workspace.value?.let { w -> runCatching { members = team.members(w.companyId) } }
    }

    fun suggestions(t: Task) = repo.suggestAssignees(t, members, allTasks.value)
    fun name(id: String?) = members.firstOrNull { it.userId == id }?.fullName

    private fun act(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block(); success?.let { message = it } } catch (e: Exception) { message = e.message ?: "Something went wrong" }
            finally { busy = false; reloadSide() }
        }
    }

    fun claim() = act {
        message = when (repo.claim(taskId)) {
            ClaimResult.SUCCESS -> { celebrate++; "Task claimed. It is now assigned to you." }
            ClaimResult.ALREADY_CLAIMED -> "Someone else claimed this task first."
            ClaimResult.NOT_ELIGIBLE -> "This task is for another department or skill group."
            ClaimResult.FORBIDDEN -> "You can't claim tasks in this workspace."
        }
    }
    fun status(t: Task, s: TaskStatus, note: String? = null, msg: String) = act(msg) { repo.setStatus(t, s, note) }
    fun release(reason: String) = act("Returned to the Open pool") { repo.release(taskId, reason) }
    fun decide(approve: Boolean, note: String?) = act {
        val r = repo.decide(taskId, approve, note)
        if (r == "APPROVED") celebrate++
        message = when (r) { "APPROVED" -> "Approved. This was the final stage."; "STAGE_APPROVED" -> "Stage approved, sent to next reviewer"; else -> "Sent back with your notes" }
    }
    fun assign(t: Task, userId: String) = act("Assigned to ${name(userId)}") { repo.assign(t, userId) }
    fun toggle(item: ChecklistItem) = viewModelScope.launch { runCatching { repo.toggleChecklist(item) } }
    fun comment(body: String) = act { repo.addComment(taskId, body) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TaskDetailScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, onAddSubtask: (String) -> Unit, vm: TaskDetailViewModel = hiltViewModel()) {
    val task by vm.task.collectAsStateWithLifecycle()
    val checklist by vm.checklist.collectAsStateWithLifecycle()
    val subtasks by vm.subtasks.collectAsStateWithLifecycle()
    val w by vm.workspace.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var showAssign by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.celebrate) { if (vm.celebrate > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    val t = task
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(t?.let { "#" + it.id.takeLast(6).uppercase() } ?: "", fontFamily = Mono, style = MaterialTheme.typography.labelLarge) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (t != null) PriorityBar(t.priority, Modifier.padding(end = 16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = { if (t != null && w != null) ActionBar(t, w!!, vm, onAsk = { dialog = it }, onAssign = { showAssign = true }) },
    ) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .6f) {
            if (t == null) { SkeletonList(3); return@AuroraBackground }
            Celebration(vm.celebrate)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(t.status)
                            Pill(t.priority.label, t.priority.color())
                            if (t.isOverdue) Pill("Overdue", Nx.Red)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(t.title, style = MaterialTheme.typography.headlineSmall)
                        if (t.description.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(t.description, style = MaterialTheme.typography.bodyMedium, color = LocalNx.current.mute)
                        }
                        if (t.status == TaskStatus.BLOCKED && t.blockedReason != null) {
                            Spacer(Modifier.height(10.dp)); Pill("Blocked: ${t.blockedReason}", Nx.Red)
                        }
                    }
                }
                item { Pipeline(t, Modifier.padding(16.dp)) }
                item {
                    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Meta(Icons.Rounded.Person, "Assignee", vm.name(t.assignedTo) ?: if (t.isOpenPool) "Open pool — claimable" else "Unassigned")
                        Meta(Icons.Rounded.Edit, "Created by", vm.name(t.createdBy) ?: "—")
                        Meta(Icons.Rounded.Schedule, "Deadline", t.dueAt?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it) + " · " + relativeTime(it) } ?: "No deadline")
                        Meta(Icons.Rounded.Verified, "Approval", t.approvalChain.mapIndexed { i, r ->
                            (if (i < t.approvalStage || t.status.isDone) "✓ " else if (i == t.approvalStage && t.status == TaskStatus.SUBMITTED) "▶ " else "") +
                                r.replaceFirstChar { it.uppercase() } }.joinToString("  →  ").ifEmpty { "None" })
                    }
                }
                if (subtasks.isNotEmpty() || w?.can(Perm.TASK_CREATE) == true) {
                    item { SectionHeader("Deliverables · ${subtasks.count { it.status.isDone }}/${subtasks.size}",
                        trailing = { if (w?.can(Perm.TASK_CREATE) == true) TextButton({ onAddSubtask(t.id) }) { Text("+ Add") } }) }
                    items(subtasks, key = { "s" + it.id }) { s ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(s.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(s.status.color()))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(vm.name(s.assignedTo) ?: if (s.isOpenPool) "Open pool" else "Unassigned", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                            }
                            StatusChip(s.status)
                        }
                    }
                }
                if (checklist.isNotEmpty()) {
                    item { SectionHeader("Checklist · ${checklist.count { it.done }}/${checklist.size}") }
                    items(checklist, key = { "c" + it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { vm.toggle(c) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.done, { vm.toggle(c) }, colors = CheckboxDefaults.colors(checkedColor = Nx.Lime, checkmarkColor = Nx.Void))
                            Text(c.label, textDecoration = if (c.done) TextDecoration.LineThrough else null,
                                color = if (c.done) LocalNx.current.mute else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    item { Text("Ticking items never approves the task — approval is a separate step.", style = MaterialTheme.typography.bodySmall,
                        color = LocalNx.current.mute, modifier = Modifier.padding(horizontal = 20.dp)) }
                }
                item { SectionHeader("Discussion") }
                items(vm.comments, key = { "m" + it.id }) { c ->
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Avatar(c.authorName, 30.dp); Spacer(Modifier.width(10.dp))
                        Column {
                            Row { Text(c.authorName, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.width(8.dp))
                                Text(relativeTime(c.at), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute) }
                            Text(c.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { CommentBox { vm.comment(it) } }
                item { SectionHeader("History") }
                items(vm.activity, key = { "a" + it.id }) { a ->
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Nx.Violet))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${a.actorName} ${a.action.replace('_', ' ')}${if (a.detail.isNotBlank() && a.detail != "{}") " · ${a.detail}" else ""}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(relativeTime(a.at), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "block" -> NoteDialog("What's blocking you?", "Reason", { dialog = null }) { vm.status(t!!, TaskStatus.BLOCKED, it, "Marked blocked") ; dialog = null }
        "release" -> NoteDialog("Return to the pool", "Why are you dropping this task?", { dialog = null }) { vm.release(it); dialog = null }
        "changes" -> NoteDialog("Request changes", "What needs to change?", { dialog = null }) { vm.decide(false, it); dialog = null }
    }
    if (showAssign && t != null) AssignSheet(t, vm, { showAssign = false })
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = LocalNx.current.mute)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The work lifecycle as a glowing track. */
@Composable
private fun Pipeline(t: Task, modifier: Modifier) {
    val steps = TaskStatus.pipeline
    val idx = when (t.status) {
        TaskStatus.BLOCKED -> 2; TaskStatus.CHANGES_REQUESTED -> 2
        TaskStatus.INBOX, TaskStatus.EVALUATION -> -1
        else -> steps.indexOf(t.status)
    }
    val glow by rememberInfiniteTransition(label = "p").animateFloat(.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "g")
    GlassCard(modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { i, s ->
                val reached = i <= idx
                val current = i == idx
                Box(Modifier.size(if (current) 16.dp else 12.dp).clip(CircleShape)
                    .background(if (reached) s.color().copy(alpha = if (current) glow else 1f) else LocalNx.current.line))
                if (i < steps.lastIndex) Box(Modifier.weight(1f).height(2.dp).background(if (i < idx) Nx.Violet else LocalNx.current.line))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            steps.forEach { s ->
                Text(s.label.substringBefore(' '), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (s == t.status) s.color() else LocalNx.current.mute, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionBar(t: Task, w: Workspace, vm: TaskDetailViewModel, onAsk: (String) -> Unit, onAssign: () -> Unit) {
    val mine = t.assignedTo == vm.me
    val manager = w.can(Perm.TASK_ASSIGN)
    val canReview = t.status == TaskStatus.SUBMITTED && w.can(Perm.TASK_APPROVE) && !mine && w.roleRank >= rankOf(t.currentStageRole)
    val primary: Pair<String, () -> Unit>? = when {
        t.status == TaskStatus.OPEN && t.isOpenPool && w.can(Perm.TASK_CLAIM) -> "Claim task" to { vm.claim() }
        canReview -> "Approve" to { vm.decide(true, null) }
        mine && t.status == TaskStatus.ASSIGNED -> "Start working" to { vm.status(t, TaskStatus.IN_PROGRESS, msg = "Task started") }
        mine && t.status == TaskStatus.IN_PROGRESS -> "Submit for review" to { vm.status(t, TaskStatus.SUBMITTED, msg = "Submitted for review") }
        mine && t.status == TaskStatus.BLOCKED -> "Resume" to { vm.status(t, TaskStatus.IN_PROGRESS, msg = "Back in progress") }
        mine && t.status == TaskStatus.CHANGES_REQUESTED -> "Resubmit" to { vm.status(t, TaskStatus.SUBMITTED, msg = "Resubmitted") }
        manager && t.status == TaskStatus.APPROVED -> "Mark published" to { vm.status(t, TaskStatus.PUBLISHED, msg = "Marked as published") }
        manager && t.status in setOf(TaskStatus.INBOX, TaskStatus.EVALUATION) -> "Open to pool" to { vm.status(t, TaskStatus.OPEN, msg = "Now in the Open pool") }
        else -> null
    }
    val secondary = buildList<Pair<String, () -> Unit>> {
        if (canReview) add("Changes" to { onAsk("changes") })
        if (mine && t.status == TaskStatus.IN_PROGRESS) add("Blocked" to { onAsk("block") })
        if (mine && t.status in setOf(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS)) add("Release" to { onAsk("release") })
        if (manager && !t.status.isDone) add(if (t.assignedTo == null) "Assign" to onAssign else "Reassign" to onAssign)
    }
    if (primary == null && secondary.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            secondary.forEach { (label, go) -> OutlinedButton(go, shape = RoundedCornerShape(14.dp), enabled = !vm.busy) { Text(label, maxLines = 1) } }
            if (primary != null) GradientButton(primary.first, Modifier.weight(1f), loading = vm.busy, onClick = primary.second)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSheet(t: Task, vm: TaskDetailViewModel, onDismiss: () -> Unit) {
    val suggestions = remember(t, vm.members) { vm.suggestions(t) }
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Smart assignment", style = MaterialTheme.typography.titleLarge)
            Text("Ranked by skill, capacity, deadline fit and team (S = 0.40K + 0.25C + 0.20D + 0.15T). You decide.",
                style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            Spacer(Modifier.height(12.dp))
            suggestions.take(8).forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.assign(t, s.member.userId); onDismiss() }
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s.member.fullName, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.member.fullName, style = MaterialTheme.typography.titleSmall)
                            if (i == 0) { Spacer(Modifier.width(8.dp)); Pill("Best fit", Nx.Lime) }
                        }
                        Text("${s.member.title.ifBlank { s.member.roleName }} · ${s.reason}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                    Text("${s.score.toInt()}", fontFamily = Mono, style = MaterialTheme.typography.titleMedium,
                        color = if (s.score > 70) Nx.Lime else if (s.score > 50) Nx.Cyan else LocalNx.current.mute)
                }
            }
        }
    }
}

@Composable
fun NoteDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, minLines = 3, colors = nxFieldColors()) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun CommentBox(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Add to the discussion…") },
            shape = RoundedCornerShape(16.dp), colors = nxFieldColors(), maxLines = 4)
        Spacer(Modifier.width(8.dp))
        FilledIconButton({ if (text.isNotBlank()) { onSend(text.trim()); text = "" } },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Nx.Violet)) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Quick radial burst when something good happens (claim won, final approval). */
@Composable
private fun Celebration(trigger: Int) {
    if (trigger == 0) return
    val p = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { p.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    if (p.value >= 1f) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * .3f)
        val colors = listOf(Nx.Cyan, Nx.Violet, Nx.Magenta, Nx.Lime, Nx.Amber)
        for (i in 0 until 28) {
            val a = (i / 28f) * 6.2832f
            val r = size.minDimension * .45f * p.value
            drawCircle(colors[i % colors.size].copy(alpha = 1f - p.value), 6f * (1f - p.value) + 2f,
                androidx.compose.ui.geometry.Offset(c.x + r * kotlin.math.cos(a), c.y + r * kotlin.math.sin(a)))
        }
        drawCircle(Nx.Cyan.copy(alpha = (1f - p.value) * .5f), size.minDimension * .3f * p.value, c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
    }
}

// ============================================================== Create task
@HiltViewModel
class CreateTaskViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: TaskRepository,
    private val team: TeamRepository,
    workspaces: WorkspaceRepository,
) : ViewModel() {
    val parentId: String? = saved.get<String>("parentId")?.takeIf { it.isNotBlank() && it != "none" }
    val workspace = workspaces.active.value
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var departments by mutableStateOf<List<Department>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            workspace?.let { w -> runCatching { members = team.members(w.companyId); departments = team.departments(w.companyId) } }
        }
    }

    fun create(d: TaskDraft, done: (String) -> Unit) {
        if (d.title.isBlank()) { error = "Give the task a title"; return }
        viewModelScope.launch {
            busy = true; error = null
            try { done(repo.create(d).id) } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateTaskScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: CreateTaskViewModel = hiltViewModel()) {
    val w = vm.workspace ?: return
    val full = w.can(Perm.TASK_CREATE)
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var dept by remember { mutableStateOf<String?>(null) }
    var pool by remember { mutableStateOf(false) }
    var assignee by remember { mutableStateOf<String?>(null) }
    var dueOpt by remember { mutableIntStateOf(2) }
    var chain by remember { mutableIntStateOf(0) }
    val checklist = remember { mutableStateListOf<String>() }
    var newItem by remember { mutableStateOf("") }
    val dues = listOf("None" to null, "Today 6 pm" to 0, "Tomorrow" to 1, "3 days" to 3, "1 week" to 7)
    val chains = listOf(listOf("lead", "head"), listOf("head"), listOf("lead"), listOf("head", "admin"))

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text(if (vm.parentId != null) "New deliverable" else if (full) "New task" else "Request a task") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Rounded.Close, "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        },
    ) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!full) GlassCard(Modifier.fillMaxWidth(), accent = Nx.Amber) {
                    Text("Your request lands in the Inbox. A manager will evaluate and assign it.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
                OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 3,
                    shape = RoundedCornerShape(16.dp), colors = nxFieldColors(),
                    supportingText = { Text("Link files from your drive — DevNex never stores media.") })

                FieldLabel("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { p -> FilterChip(priority == p, { priority = p }, label = { Text(p.label) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(p.color())) }) }
                }
                FieldLabel("Department")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(dept == null, { dept = null }, label = { Text("Any") })
                    vm.departments.forEach { d -> FilterChip(dept == d.id, { dept = d.id }, label = { Text(d.name) }) }
                }
                if (full) {
                    FieldLabel("Who does it?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!pool, { pool = false }, label = { Text("Assign someone") }, leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(16.dp)) })
                        FilterChip(pool, { pool = true; assignee = null }, label = { Text("Open Work Pool") }, leadingIcon = { Icon(Icons.Rounded.Waves, null, Modifier.size(16.dp)) })
                    }
                    if (!pool) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vm.members.filter { dept == null || it.departmentId == dept || it.roleRank >= 70 }.forEach { m ->
                            FilterChip(assignee == m.userId, { assignee = if (assignee == m.userId) null else m.userId },
                                label = { Text(m.fullName.substringBefore(' ') + if (m.activeTasks > 0) " · ${m.activeTasks}" else "") },
                                leadingIcon = { Avatar(m.fullName, 20.dp) })
                        }
                    } else Text("Eligible members of the chosen department can claim it. The first valid claim wins — the server guarantees exactly one owner.",
                        style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                }
                FieldLabel("Deadline")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dues.forEachIndexed { i, (l, _) -> FilterChip(dueOpt == i, { dueOpt = i }, label = { Text(l) }) }
                }
                FieldLabel("Approval chain")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chains.forEachIndexed { i, c -> FilterChip(chain == i, { chain = i },
                        label = { Text(c.joinToString(" → ") { it.replaceFirstChar { ch -> ch.uppercase() } }) }) }
                }
                FieldLabel("Checklist")
                checklist.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckBoxOutlineBlank, null, tint = LocalNx.current.mute); Spacer(Modifier.width(8.dp))
                        Text(item, Modifier.weight(1f))
                        IconButton({ checklist.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), placeholder = { Text("Add a step") }, singleLine = true,
                        shape = RoundedCornerShape(14.dp), colors = nxFieldColors())
                    IconButton({ if (newItem.isNotBlank()) { checklist += newItem.trim(); newItem = "" } }) { Icon(Icons.Rounded.AddCircle, "Add", tint = Nx.Cyan) }
                }
                AnimatedVisibility(vm.error != null) { Text(vm.error.orEmpty(), color = Nx.Red) }
                GradientButton(if (full) "Create task" else "Send request", Modifier.fillMaxWidth(), loading = vm.busy, icon = Icons.Rounded.FlashOn) {
                    val days = dues[dueOpt].second
                    val due = days?.let {
                        val c = java.util.Calendar.getInstance()
                        if (it == 0) { c.set(java.util.Calendar.HOUR_OF_DAY, 18); c.set(java.util.Calendar.MINUTE, 0) } else c.add(java.util.Calendar.DAY_OF_YEAR, it)
                        c.timeInMillis
                    }
                    vm.create(TaskDraft(w.companyId, title, desc, priority, dept, assignee, pool, due, vm.parentId, checklist.toList(), chains[chain]), onCreated)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
