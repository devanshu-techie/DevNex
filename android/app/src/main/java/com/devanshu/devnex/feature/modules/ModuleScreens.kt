package com.devanshu.devnex.feature.modules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.ModulesRepository
import com.devanshu.devnex.domain.repository.TeamRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val repo: ModulesRepository,
    private val team: TeamRepository,
    workspaces: WorkspaceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val workspace = workspaces.active.value
    val me = auth.session.value?.id
    var slots by mutableStateOf<List<PublishingSlot>?>(null); private set
    var ideas by mutableStateOf<List<Idea>?>(null); private set
    var ops by mutableStateOf<List<OpsEntry>?>(null); private set
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    private val cid get() = workspace?.companyId.orEmpty()

    fun loadSlots() = viewModelScope.launch { runCatching { slots = repo.slots(cid) }.onFailure { message = it.message; slots = emptyList() } }
    fun loadIdeas() = viewModelScope.launch { runCatching { ideas = repo.ideas(cid) }.onFailure { message = it.message; ideas = emptyList() } }
    fun loadOps() = viewModelScope.launch { runCatching { ops = repo.opsLog(cid) }.onFailure { message = it.message; ops = emptyList() } }
    fun loadMembers() = viewModelScope.launch { runCatching { members = team.members(cid) } }

    private fun act(ok: String?, after: () -> Unit, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block(); if (ok != null) message = ok; after() } catch (e: Exception) { message = e.message } finally { busy = false }
        }
    }

    fun createSlot(title: String, platform: String, ownerId: String?, at: Long, caption: String) =
        act("Added to the calendar", { loadSlots() }) { repo.createSlot(cid, title, platform, ownerId, at, caption) }
    fun publish(slot: PublishingSlot, url: String) = act("Publication recorded", { loadSlots() }) { repo.publishSlot(slot.id, url) }
    fun setSlotStatus(slot: PublishingSlot, status: String) = act("Updated", { loadSlots() }) { repo.setSlotStatus(slot.id, status) }
    fun createIdea(title: String, desc: String, impact: String, ownerId: String?) =
        act("Idea added", { loadIdeas() }) { repo.createIdea(cid, title, desc, impact, ownerId, null) }
    fun setIdeaStatus(idea: Idea, status: String) = act("Idea moved to ${status.replaceFirstChar { it.uppercase() }}", { loadIdeas() }) { repo.setIdeaStatus(idea.id, status) }
    fun convert(idea: Idea, open: (String) -> Unit) = act("Task created from idea", { loadIdeas() }) { val id = repo.convertIdea(idea.id); open(id) }
    fun addOps(category: String, body: String) = act("Entry added", { loadOps() }) { repo.addOps(cid, category, body) }
}

private val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

private fun dayKey(ms: Long): Long { val c = Calendar.getInstance(); c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis }

fun dayLabel(ms: Long): String {
    val today = dayKey(System.currentTimeMillis()); val d = dayKey(ms)
    return when (d) { today -> "Today"; today + 86_400_000L -> "Tomorrow"; today - 86_400_000L -> "Yesterday"; else -> dayFmt.format(ms) }
}

@Composable
private fun Snack(vm: ModulesViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm.message) { vm.message?.let { host.showSnackbar(it); vm.message = null } }
}

// ============================================================== Publishing calendar
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishingScreen(onBack: () -> Unit, vm: ModulesViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadSlots(); vm.loadMembers() }
    val host = remember { SnackbarHostState() }; Snack(vm, host)
    var filter by remember { mutableStateOf("upcoming") }
    var showAdd by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf<PublishingSlot?>(null) }
    val slots = vm.slots
    Scaffold(
        topBar = { NxTopBar("Publishing calendar", onBack, subtitle = "Social media and website schedule") },
        snackbarHost = { SnackbarHost(host) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Schedule") },
                containerColor = Nx.Violet, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("upcoming" to "Upcoming", "draft" to "Drafts", "published" to "Published", "all" to "All").forEach { (k, l) ->
                        FilterChip(filter == k, { filter = k }, label = { Text(l) })
                    }
                }
            }
            if (slots == null) item { SkeletonList(3) } else {
                val now = System.currentTimeMillis()
                val shown = slots.filter {
                    when (filter) {
                        "upcoming" -> it.status in setOf("scheduled", "draft") && it.scheduledAt >= now - 3_600_000
                        "draft" -> it.status == "draft"; "published" -> it.status == "published"; else -> true
                    }
                }.let { if (filter == "published") it.sortedByDescending { s -> s.publishedAt ?: s.scheduledAt } else it }
                if (shown.isEmpty()) item {
                    EmptyState(Icons.Rounded.DateRange, "Nothing scheduled", "Plan posts, videos and articles with an owner and a time. Record the link once it goes live.")
                }
                shown.groupBy { dayKey(it.scheduledAt) }.forEach { (day, list) ->
                    item(key = "d$day") { SectionHeader(dayLabel(day)) }
                    items(list, key = { it.id }) { s -> SlotCard(s, vm.me, vm.workspace) { publishing = s } }
                }
            }
        }
    }
    if (showAdd) AddSlotDialog(vm.members, { showAdd = false }) { t, p, o, at, c -> vm.createSlot(t, p, o, at, c); showAdd = false }
    publishing?.let { s ->
        var url by remember(s.id) { mutableStateOf("https://") }
        AlertDialog(onDismissRequest = { publishing = null }, title = { Text("Record publication") },
            text = { Column { Text("Paste the live link for “${s.title}”.", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(10.dp))
                OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("Published link") }, colors = nxFieldColors()) } },
            confirmButton = { TextButton({ vm.publish(s, url); publishing = null }, enabled = url.length > 10) { Text("Mark published") } },
            dismissButton = { TextButton({ publishing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SlotCard(s: PublishingSlot, me: String?, w: Workspace?, onPublish: () -> Unit) {
    val color = when (s.status) { "published" -> Nx.Lime; "scheduled" -> Nx.Violet; "cancelled" -> Nx.Mute; else -> Nx.Amber }
    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeFmt.format(s.scheduledAt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(76.dp))
            Text(Platforms.label(s.platform), style = MaterialTheme.typography.labelMedium, color = LocalNx.current.mute, modifier = Modifier.weight(1f))
            Pill(s.status.replaceFirstChar { it.uppercase() }, color)
        }
        Spacer(Modifier.height(6.dp))
        Text(s.title, style = MaterialTheme.typography.titleMedium)
        if (s.caption.isNotBlank()) Text(s.caption, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.ownerName != null) { Avatar(s.ownerName, 22.dp); Spacer(Modifier.width(6.dp)); Text(s.ownerName, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.weight(1f))
            val canPublish = s.status != "published" && (s.ownerId == me || w?.can(Perm.TASK_ASSIGN) == true)
            if (canPublish) TextButton(onPublish) { Text("Mark published") }
        }
        if (s.publishedUrl != null) {
            Text(s.publishedUrl, style = MaterialTheme.typography.bodySmall, color = Nx.Cyan, maxLines = 1)
            if (s.views != null) Text("${s.views} views · ${s.engagement ?: 0} engagements", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSlotDialog(members: List<Member>, onDismiss: () -> Unit, onSave: (String, String, String?, Long, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("instagram") }
    var owner by remember { mutableStateOf<String?>(null) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var hour by remember { mutableIntStateOf(18) }
    var caption by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Schedule content") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("What is being published") }, singleLine = true, colors = nxFieldColors())
                Text("Platform", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Platforms.all.forEach { p -> FilterChip(platform == p, { platform = p }, label = { Text(Platforms.label(p)) }) }
                }
                Text("Day", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Today", 1 to "Tomorrow", 2 to "In 2 days", 7 to "Next week").forEach { (d, l) -> FilterChip(dayOffset == d, { dayOffset = d }, label = { Text(l) }) }
                }
                Text("Time", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(9, 12, 15, 18, 21).forEach { h -> FilterChip(hour == h, { hour = h }, label = { Text(if (h < 12) "$h AM" else if (h == 12) "12 PM" else "${h - 12} PM") }) }
                }
                Text("Owner", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(owner == null, { owner = null }, label = { Text("Me") })
                    members.forEach { m -> FilterChip(owner == m.userId, { owner = m.userId }, label = { Text(m.fullName.substringBefore(' ')) }) }
                }
                OutlinedTextField(caption, { caption = it }, label = { Text("Caption or notes") }, minLines = 2, colors = nxFieldColors())
            }
        },
        confirmButton = {
            TextButton({
                val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, dayOffset); c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0)
                onSave(title, platform, owner, c.timeInMillis, caption)
            }, enabled = title.isNotBlank()) { Text("Schedule") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ============================================================== Ideas board
@Composable
fun IdeasScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, vm: ModulesViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadIdeas(); vm.loadMembers() }
    val host = remember { SnackbarHostState() }; Snack(vm, host)
    var filter by remember { mutableStateOf("open") }
    var showAdd by remember { mutableStateOf(false) }
    val manager = vm.workspace?.can(Perm.TASK_ASSIGN) == true
    Scaffold(
        topBar = { NxTopBar("Ideas board", onBack, subtitle = "Future work that isn't a task yet") },
        snackbarHost = { SnackbarHost(host) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Rounded.Lightbulb, null) }, text = { Text("New idea") },
                containerColor = Nx.Violet, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("open" to "Open", "approved" to "Approved", "parked" to "Parked", "converted" to "Converted").forEach { (k, l) ->
                        FilterChip(filter == k, { filter = k }, label = { Text(l) })
                    }
                }
            }
            val ideas = vm.ideas
            if (ideas == null) item { SkeletonList(3) } else {
                val shown = ideas.filter { if (filter == "open") it.status in setOf("new", "reviewing") else it.status == filter }
                if (shown.isEmpty()) item { EmptyState(Icons.Rounded.Lightbulb, "No ideas here", "Capture podcast series, campaigns or improvements. Managers review them and turn the good ones into tasks.") }
                items(shown, key = { it.id }) { i ->
                    val impactColor = when (i.impact) { "high" -> Nx.Red; "low" -> Nx.Mute; else -> Nx.Amber }
                    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("${i.impact.replaceFirstChar { it.uppercase() }} impact", impactColor)
                            Spacer(Modifier.weight(1f))
                            Text(i.status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = LocalNx.current.mute)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(i.title, style = MaterialTheme.typography.titleMedium)
                        if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.bodyMedium, color = LocalNx.current.mute)
                        Spacer(Modifier.height(8.dp))
                        Text("Proposed by ${i.createdByName}" + (i.ownerName?.let { " · Owner: $it" } ?: "") +
                            (i.reviewDate?.let { " · Review ${SimpleDateFormat("d MMM", Locale.getDefault()).format(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        if (manager && i.status != "converted") {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (i.status == "new") OutlinedButton({ vm.setIdeaStatus(i, "reviewing") }) { Text("Review") }
                                if (i.status != "approved") OutlinedButton({ vm.setIdeaStatus(i, "approved") }) { Text("Approve") }
                                if (i.status != "parked") TextButton({ vm.setIdeaStatus(i, "parked") }) { Text("Park") }
                                if (i.status == "approved") Button({ vm.convert(i, onOpenTask) }) { Text("Make task") }
                            }
                        }
                        if (i.convertedTaskId != null) TextButton({ onOpenTask(i.convertedTaskId) }) { Text("Open task") }
                    }
                }
            }
        }
    }
    if (showAdd) {
        var title by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }
        var impact by remember { mutableStateOf("medium") }; var owner by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("New idea") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Idea") }, singleLine = true, colors = nxFieldColors())
                OutlinedTextField(desc, { desc = it }, label = { Text("Why it matters") }, minLines = 2, colors = nxFieldColors())
                Text("Expected impact", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("low", "medium", "high").forEach { k -> FilterChip(impact == k, { impact = k }, label = { Text(k.replaceFirstChar { it.uppercase() }) }) }
                }
                Text("Proposed owner", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(owner == null, { owner = null }, label = { Text("Not decided") })
                    vm.members.forEach { m -> FilterChip(owner == m.userId, { owner = m.userId }, label = { Text(m.fullName.substringBefore(' ')) }) }
                }
            } },
            confirmButton = { TextButton({ vm.createIdea(title, desc, impact, owner); showAdd = false }, enabled = title.isNotBlank()) { Text("Add idea") } },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } })
    }
}

// ============================================================== Operations log
private val opsCategories = listOf("decision" to "Decision", "incident" to "Incident", "delay" to "Delay", "handover" to "Handover", "note" to "Note")

@Composable
fun OpsLogScreen(onBack: () -> Unit, vm: ModulesViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadOps() }
    val host = remember { SnackbarHostState() }; Snack(vm, host)
    var filter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("note") }
    var body by remember { mutableStateOf("") }
    Scaffold(
        topBar = { NxTopBar("Operations log", onBack, subtitle = "Decisions, incidents and handovers on record") },
        snackbarHost = { SnackbarHost(host) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                GlassCard(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("Add an entry", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        opsCategories.forEach { (k, l) -> FilterChip(category == k, { category = k }, label = { Text(l) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), placeholder = { Text("What happened, and what was decided?") },
                        minLines = 2, colors = nxFieldColors())
                    Spacer(Modifier.height(8.dp))
                    GradientButton("Add to log", Modifier.fillMaxWidth(), enabled = body.isNotBlank(), loading = vm.busy) { vm.addOps(category, body); body = "" }
                    Text("Entries can't be edited or deleted, so the log stays a reliable record.", style = MaterialTheme.typography.bodySmall,
                        color = LocalNx.current.mute, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search the log") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    opsCategories.forEach { (k, l) -> FilterChip(filter == k, { filter = k }, label = { Text(l) }) }
                }
            }
            val ops = vm.ops
            if (ops == null) item { SkeletonList(3) } else {
                val shown = ops.filter { (filter == null || it.category == filter) && (query.isBlank() || it.body.contains(query, true)) }
                if (shown.isEmpty()) item { EmptyState(Icons.Rounded.History, "No entries", "Record delays, incidents and decisions so the whole team knows why things changed.") }
                items(shown, key = { it.id }) { e ->
                    val c = when (e.category) { "incident" -> Nx.Red; "delay" -> Nx.Amber; "decision" -> Nx.Violet; "handover" -> Nx.Cyan; else -> Nx.Mute }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(opsCategories.firstOrNull { it.first == e.category }?.second ?: e.category, c)
                                Spacer(Modifier.width(8.dp))
                                Text("${e.authorName} · ${dateTimeFmt.format(e.at)}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(e.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalNx.current.line)
                }
            }
        }
    }
}
