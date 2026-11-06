package com.devanshu.devnex.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
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
import com.devanshu.devnex.feature.dashboard.rankOf
import com.devanshu.devnex.feature.modules.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val tasksRepo: TaskRepository,
    private val team: TeamRepository,
    private val coverage: CoverageRepository,
    private val modules: ModulesRepository,
    private val chat: ChatRepository,
    private val admin: AdminRepository,
    private val auth: AuthRepository,
    workspaces: WorkspaceRepository,
) : ViewModel() {
    val workspace = workspaces.active.value
    val user = auth.session
    private val cid get() = workspace?.companyId.orEmpty()
    val tasks: StateFlow<List<Task>> = workspaces.active.flatMapLatest { w -> if (w == null) flowOf(emptyList()) else tasksRepo.observeTasks(w.companyId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var events by mutableStateOf<List<CoverageEvent>>(emptyList()); private set
    var slots by mutableStateOf<List<PublishingSlot>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            runCatching { members = team.members(cid) }
            if (workspace?.has(Modules.COVERAGE) == true) runCatching { events = coverage.events(cid) }
            if (workspace?.has(Modules.PUBLISHING) == true) runCatching { slots = modules.slots(cid) }
        }
    }

    fun name(id: String?) = members.firstOrNull { it.userId == id }?.fullName

    private fun act(ok: String?, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block(); if (ok != null) message = ok } catch (e: Exception) { message = e.message } finally { busy = false }
        }
    }
    fun decide(t: Task, approve: Boolean, note: String?) = act(if (approve) "Approved: ${t.title}" else "Sent back: ${t.title}") { tasksRepo.decide(t.id, approve, note) }
    fun openDm(other: String, done: (String, String) -> Unit) = act(null) { val id = chat.openDirect(cid, other); done(id, name(other) ?: "Direct message") }
    fun saveProfile(name: String, title: String, availability: String, skills: List<String>, done: () -> Unit) = act("Profile saved") {
        if (name.trim() != user.value?.fullName) auth.updateName(name)
        admin.updateMyProfile(cid, title, availability, skills); done()
    }
}

@Composable
private fun SnackHost(vm: ToolsViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { host.showSnackbar(it); vm.message = null } }
    return host
}

// ============================================================== Approvals inbox
@Composable
fun ApprovalsScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val host = SnackHost(vm)
    val me = vm.user.collectAsStateWithLifecycle().value?.id
    val w = vm.workspace
    var sendBack by remember { mutableStateOf<Task?>(null) }
    val mine = tasks.filter { it.status == TaskStatus.SUBMITTED && it.assignedTo != me && w?.can(Perm.TASK_APPROVE) == true && w.roleRank >= rankOf(it.currentStageRole) }
    val waiting = tasks.filter { it.status == TaskStatus.SUBMITTED && it !in mine }
    Scaffold(topBar = { NxTopBar("Approvals", onBack, subtitle = "${mine.size} waiting for you") }, snackbarHost = { SnackbarHost(host) },
        containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Waiting for your decision") }
            if (mine.isEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "Nothing to approve", "Submitted work that needs your stage of approval appears here.") }
            items(mine, key = { "m" + it.id }) { t ->
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), onClick = { onOpenTask(t.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriorityBar(t.priority); Spacer(Modifier.width(8.dp))
                        Text("Stage ${t.approvalStage + 1} of ${t.approvalChain.size} · ${t.currentStageRole?.replaceFirstChar { it.uppercase() } ?: ""}",
                            style = MaterialTheme.typography.labelMedium, color = LocalNx.current.mute, modifier = Modifier.weight(1f))
                        if (t.isOverdue) Pill("Overdue", Nx.Red)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text("Submitted by ${vm.name(t.assignedTo) ?: "—"} · due ${relativeTime(t.dueAt)}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ sendBack = t }, enabled = !vm.busy) { Text("Request changes") }
                        Button({ vm.decide(t, true, null) }, enabled = !vm.busy) { Text("Approve") }
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item { SectionHeader("In review with others") }
                items(waiting, key = { "w" + it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Waiting for ${t.currentStageRole?.replaceFirstChar { it.uppercase() } ?: "review"}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        }
                        StatusChip(t.status)
                    }
                }
            }
        }
    }
    sendBack?.let { t ->
        var note by remember(t.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { sendBack = null }, title = { Text("Request changes") },
            text = { OutlinedTextField(note, { note = it }, label = { Text("What needs to change?") }, minLines = 3, colors = nxFieldColors()) },
            confirmButton = { TextButton({ vm.decide(t, false, note); sendBack = null }, enabled = note.isNotBlank()) { Text("Send back") } },
            dismissButton = { TextButton({ sendBack = null }) { Text("Cancel") } })
    }
}

// ============================================================== Calendar
private sealed class CalItem(val at: Long) {
    class TaskItem(val t: Task) : CalItem(t.dueAt ?: 0)
    class EventItem(val e: CoverageEvent) : CalItem(e.eventDate)
    class SlotItem(val s: PublishingSlot) : CalItem(s.scheduledAt)
}

@Composable
fun CalendarScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val me = vm.user.collectAsStateWithLifecycle().value?.id
    var onlyMine by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val start = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis }
    val end = start + 22L * 86_400_000L
    val items = remember(tasks, vm.events, vm.slots, onlyMine, me) {
        (tasks.filter { it.dueAt != null && !it.status.isDone && it.status != TaskStatus.CANCELLED && (!onlyMine || it.assignedTo == me) }.map { CalItem.TaskItem(it) } +
            (if (onlyMine) emptyList() else vm.events.map { CalItem.EventItem(it) }) +
            vm.slots.filter { !onlyMine || it.ownerId == me }.map { CalItem.SlotItem(it) })
            .filter { it.at in start..end }.sortedBy { it.at }
    }
    val overdue = tasks.filter { it.isOverdue && (!onlyMine || it.assignedTo == me) }
    Scaffold(topBar = { NxTopBar("Calendar", onBack, subtitle = "Deadlines, events and publishing — next 3 weeks") },
        containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlyMine, { onlyMine = false }, label = { Text("Everything") })
                    FilterChip(onlyMine, { onlyMine = true }, label = { Text("Only mine") })
                }
            }
            if (overdue.isNotEmpty()) item {
                GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), accent = Nx.Red) {
                    Text("${overdue.size} overdue", style = MaterialTheme.typography.titleSmall, color = Nx.Red)
                    overdue.take(4).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (items.isEmpty()) item { EmptyState(Icons.Rounded.DateRange, "A clear schedule", "Tasks with deadlines, coverage events and scheduled posts appear here by day.") }
            items.groupBy { val c = Calendar.getInstance(); c.timeInMillis = it.at; c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }.forEach { (key, list) ->
                item(key = "day$key") { SectionHeader(dayLabel(list.first().at)) }
                items(list.size) { i ->
                    val it = list[i]
                    val (title, kind, color, click) = when (it) {
                        is CalItem.TaskItem -> Quad(it.t.title, "Task deadline · ${vm.name(it.t.assignedTo) ?: if (it.t.isOpenPool) "Open pool" else "Unassigned"}", it.t.priority.color(), { onOpenTask(it.t.id) })
                        is CalItem.EventItem -> Quad(it.e.name, "Coverage event · ${it.e.venue}", Nx.Magenta, {})
                        is CalItem.SlotItem -> Quad(it.s.title, "${Platforms.label(it.s.platform)} · ${it.s.ownerName ?: ""}", Nx.Cyan, {})
                    }
                    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (it is CalItem.EventItem) "All day" else timeFmt.format(it.at), style = MaterialTheme.typography.bodySmall,
                            color = LocalNx.current.mute, modifier = Modifier.width(64.dp))
                        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(kind, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: androidx.compose.ui.graphics.Color, val d: () -> Unit)

// ============================================================== Search
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, onOpenDm: (String, String) -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val tHits = if (q.length < 2) emptyList() else tasks.filter { it.title.contains(q, true) || it.description.contains(q, true) }.take(20)
    val mHits = if (q.length < 2) emptyList() else vm.members.filter { it.fullName.contains(q, true) || it.title.contains(q, true) || it.skills.any { s -> s.contains(q, true) } }
    val eHits = if (q.length < 2) emptyList() else vm.events.filter { it.name.contains(q, true) || it.sport.contains(q, true) }
    Scaffold(topBar = { NxTopBar("Search", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus), placeholder = { Text("Tasks, people, skills, events") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
            }
            if (q.length >= 2 && tHits.isEmpty() && mHits.isEmpty() && eHits.isEmpty()) item { EmptyState(Icons.Rounded.Search, "No results", "Try a shorter word, a person's first name or a skill.") }
            if (tHits.isNotEmpty()) { item { SectionHeader("Tasks · ${tHits.size}") }
                items(tHits, key = { "t" + it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vm.name(t.assignedTo) ?: "Unassigned", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute) }
                        StatusChip(t.status)
                    }
                } }
            if (mHits.isNotEmpty()) { item { SectionHeader("People · ${mHits.size}") }
                items(mHits, key = { "p" + it.userId }) { m ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.fullName, 36.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(m.fullName); Text("${m.title.ifBlank { m.roleName }} · ${m.activeTasks} active", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute) }
                        if (m.userId != vm.user.value?.id) TextButton({ vm.openDm(m.userId, onOpenDm) }) { Text("Message") }
                    }
                } }
            if (eHits.isNotEmpty()) { item { SectionHeader("Events · ${eHits.size}") }
                items(eHits, key = { "e" + it.id }) { e -> Text("${e.name} · ${e.venue}", Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) } }
        }
    }
}

// ============================================================== New direct message
@Composable
fun NewMessageScreen(onBack: () -> Unit, onOpenDm: (String, String) -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val me = vm.user.collectAsStateWithLifecycle().value?.id
    var q by remember { mutableStateOf("") }
    Scaffold(topBar = { NxTopBar("New message", onBack, subtitle = "Private, text-only conversation") }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Find a colleague") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
            }
            items(vm.members.filter { it.userId != me && (q.isBlank() || it.fullName.contains(q, true)) }, key = { it.userId }) { m ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.openDm(m.userId, onOpenDm) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.fullName, 40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(m.fullName, style = MaterialTheme.typography.titleSmall)
                        Text(m.title.ifBlank { m.roleName }, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute) }
                    AvailabilityDot(m.availability)
                }
            }
        }
    }
}

@Composable
fun AvailabilityDot(a: String) {
    val (c, l) = when (a) { "available" -> Nx.Lime to "Available"; "busy" -> Nx.Amber to "Busy"; else -> Nx.Mute to "On leave" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
        Text(l, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
    }
}

// ============================================================== My profile
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    val host = SnackHost(vm)
    val meMember = vm.members.firstOrNull { it.userId == user?.id }
    var name by remember(user?.fullName) { mutableStateOf(user?.fullName.orEmpty()) }
    var title by remember(meMember) { mutableStateOf(meMember?.title.orEmpty()) }
    var availability by remember(meMember) { mutableStateOf(meMember?.availability ?: "available") }
    var skills by remember(meMember) { mutableStateOf(meMember?.skills?.joinToString(", ").orEmpty()) }
    Scaffold(topBar = { NxTopBar("My profile", onBack) }, snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name.ifBlank { "?" }, 56.dp); Spacer(Modifier.width(14.dp))
                Column { Text(user?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text("${vm.workspace?.roleName ?: ""} · ${vm.workspace?.name ?: ""}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute) }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true, colors = nxFieldColors())
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Job title in this company") }, singleLine = true, colors = nxFieldColors())
            Text("Availability", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("available" to "Available", "busy" to "Busy", "leave" to "On leave").forEach { (k, l) -> FilterChip(availability == k, { availability = k }, label = { Text(l) }) }
            }
            Text("Managers see this when assigning work. On leave removes you from assignment suggestions.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            OutlinedTextField(skills, { skills = it }, Modifier.fillMaxWidth(), label = { Text("Skills (comma separated)") },
                supportingText = { Text("Used to match you with open work, e.g. video, graphics, reporting") }, colors = nxFieldColors())
            GradientButton("Save profile", Modifier.fillMaxWidth(), loading = vm.busy, enabled = name.isNotBlank()) {
                vm.saveProfile(name, title, availability, skills.split(',').map { it.trim() }.filter { it.isNotBlank() }) {}
            }
        }
    }
}

// ============================================================== Edit task
@HiltViewModel
class EditTaskViewModel @Inject constructor(saved: SavedStateHandle, private val repo: TaskRepository) : ViewModel() {
    val taskId: String = checkNotNull(saved["taskId"])
    val task = repo.observeTask(taskId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    fun save(t: Task, title: String, desc: String, p: Priority, due: Long?, done: () -> Unit) = viewModelScope.launch {
        busy = true; error = null
        try { repo.edit(t, title, desc, p, due); done() } catch (e: Exception) { error = e.message } finally { busy = false }
    }
}

@Composable
fun EditTaskScreen(onBack: () -> Unit, vm: EditTaskViewModel = hiltViewModel()) {
    val t = vm.task.collectAsStateWithLifecycle().value
    Scaffold(topBar = { NxTopBar("Edit task", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        if (t == null) { Box(Modifier.padding(pad)) { SkeletonList(2) }; return@Scaffold }
        var title by remember(t.id) { mutableStateOf(t.title) }
        var desc by remember(t.id) { mutableStateOf(t.description) }
        var priority by remember(t.id) { mutableStateOf(t.priority) }
        var dueDays by remember(t.id) { mutableStateOf<Int?>(-1) }   // -1 = keep current
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, colors = nxFieldColors())
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 4, colors = nxFieldColors())
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Priority.entries.forEach { p -> FilterChip(priority == p, { priority = p }, label = { Text(p.label) }) } }
            Text("Deadline", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "Keep (${relativeTime(t.dueAt)})", 0 to "Today 6 PM", 1 to "Tomorrow", 3 to "3 days", 7 to "1 week", null to "No deadline").forEach { (d, l) ->
                    FilterChip(dueDays == d, { dueDays = d }, label = { Text(l) })
                }
            }
            vm.error?.let { Text(it, color = Nx.Red) }
            Text("Status, assignee and approvals change through the task's own actions, so the history stays accurate.",
                style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            GradientButton("Save changes", Modifier.fillMaxWidth(), loading = vm.busy, enabled = title.isNotBlank()) {
                val due = when (val d = dueDays) {
                    -1 -> t.dueAt; null -> null
                    else -> Calendar.getInstance().apply { if (d == 0) { set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0) } else add(Calendar.DAY_OF_YEAR, d) }.timeInMillis
                }
                vm.save(t, title, desc, priority, due, onBack)
            }
        }
    }
}

// ============================================================== Forgot password
@HiltViewModel
class ForgotViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    var sent by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    fun send(email: String) = viewModelScope.launch {
        busy = true; error = null
        try { auth.resetPassword(email); sent = true } catch (e: Exception) { error = e.message } finally { busy = false }
    }
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, vm: ForgotViewModel = hiltViewModel()) {
    var email by remember { mutableStateOf("") }
    Scaffold(topBar = { NxTopBar("Reset password", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (vm.sent) {
                EmptyState(Icons.Rounded.Email, "Check your inbox", "If an account exists for $email, we've sent a link to set a new password. The link expires in one hour.")
                GradientButton("Back to sign in", Modifier.fillMaxWidth(), onClick = onBack)
            } else {
                Text("Enter your work email and we'll send you a link to set a new password.", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Work email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = nxFieldColors())
                vm.error?.let { Text(it, color = Nx.Red) }
                GradientButton("Send reset link", Modifier.fillMaxWidth(), enabled = email.contains('@'), loading = vm.busy) { vm.send(email) }
            }
        }
    }
}
