package com.devanshu.devnex.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.*
import com.devanshu.devnex.feature.chat.ChannelsTab
import com.devanshu.devnex.feature.coverage.CoverageTab
import com.devanshu.devnex.feature.tasks.ProjectsTab
import com.devanshu.devnex.feature.tasks.TaskCard
import com.devanshu.devnex.feature.tasks.WorkTab
import com.devanshu.devnex.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Shared state for everything inside the main shell, scoped to the active company. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val workspaces: WorkspaceRepository,
    private val tasksRepo: TaskRepository,
    private val team: TeamRepository,
    private val notifications: NotificationRepository,
) : ViewModel() {
    val user = auth.session
    val workspace = workspaces.active
    val isDemo get() = auth.isDemo

    val tasks: StateFlow<List<Task>> = workspace.flatMapLatest { w ->
        if (w == null) flowOf(emptyList()) else tasksRepo.observeTasks(w.companyId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<AppNotification>> = workspace.flatMapLatest { w ->
        if (w == null) flowOf(emptyList()) else notifications.observe(w.companyId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingSync = tasksRepo.pendingSyncCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    var stats by mutableStateOf<DashboardStats?>(null); private set
    var members by mutableStateOf<List<Member>>(emptyList()); private set
    var departments by mutableStateOf<List<Department>>(emptyList()); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false); private set

    private var liveJob: Job? = null

    init {
        // Re-scope everything when the company changes: old realtime subscriptions are cancelled.
        viewModelScope.launch {
            workspace.map { it?.companyId }.distinctUntilChanged().collect { cid ->
                liveJob?.cancel()
                stats = null; members = emptyList(); departments = emptyList()
                if (cid != null) {
                    refresh()
                    liveJob = launch {
                        tasksRepo.live(cid).collect {
                            if (it == "notifications") runCatching { notifications.refresh(cid) }
                            runCatching { stats = tasksRepo.dashboard(cid) }
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        val cid = workspace.value?.companyId ?: return
        viewModelScope.launch {
            refreshing = true
            try {
                tasksRepo.refresh(cid)
                stats = tasksRepo.dashboard(cid)
                members = team.members(cid)
                departments = team.departments(cid)
                notifications.refresh(cid)
                offline = false
            } catch (e: Exception) {
                offline = true   // cached data stays on screen
            } finally { refreshing = false }
        }
    }

    fun memberName(id: String?) = members.firstOrNull { it.userId == id }?.fullName
    fun departmentName(id: String?) = departments.firstOrNull { it.id == id }?.name
    fun signOut(done: () -> Unit) = viewModelScope.launch { auth.signOut(); workspaces.clear(); done() }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Dashboard),
    WORK("My Work", Icons.Rounded.CheckCircle),
    DESK("Coverage", Icons.Rounded.EmojiEvents),
    CHAT("Messages", Icons.Rounded.Forum),
    MORE("More", Icons.Rounded.Apps),
}

data class MainNav(
    val openTask: (String) -> Unit,
    val createTask: (String?) -> Unit,
    val openChannel: (String, String) -> Unit,
    val openEvent: (String, String) -> Unit,
    val switchWorkspace: () -> Unit,
    val notifications: () -> Unit,
    val team: () -> Unit,
    val insights: () -> Unit,
    val admin: () -> Unit,
    val platform: () -> Unit,
    val settings: () -> Unit,
    val signedOut: () -> Unit,
    /** Opens any secondary screen by route (see navigation.Routes). */
    val go: (String) -> Unit = {},
)

@Composable
fun MainScreen(nav: MainNav, vm: MainViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val w by vm.workspace.collectAsStateWithLifecycle()
    val coverageOn = w?.has(Modules.COVERAGE) == true

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Tab.entries.forEach { t ->
                    val label = if (t == Tab.DESK && !coverageOn) "Projects" else t.label
                    val icon = if (t == Tab.DESK && !coverageOn) Icons.Rounded.AccountTree else t.icon
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = {
                            val unread = vm.notes.collectAsStateWithLifecycle().value.count { !it.read }
                            if (t == Tab.HOME && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Icon(icon, label) }
                            else Icon(icon, label)
                        },
                        label = { Text(label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Nx.Violet, selectedTextColor = Nx.Violet,
                            indicatorColor = Nx.Violet.copy(alpha = .12f), unselectedIconColor = Nx.Mute, unselectedTextColor = Nx.Mute),
                    )
                }
            }
        },
    ) { pad ->
        AuroraBackground(Modifier.padding(bottom = pad.calculateBottomPadding()), intensity = .7f) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeTab(vm, nav)
                    Tab.WORK -> WorkTab(vm, nav)
                    Tab.DESK -> if (coverageOn) CoverageTab(vm, nav) else ProjectsTab(vm, nav)
                    Tab.CHAT -> ChannelsTab(vm, nav)
                    Tab.MORE -> MoreTab(vm, nav)
                }
            }
        }
    }
}

@Composable
fun WorkspaceHeader(vm: MainViewModel, nav: MainNav, title: String? = null) {
    val w by vm.workspace.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val pending by vm.pendingSync.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = nav.switchWorkspace).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(w?.brandColor ?: 0xFF7C5CFF)), contentAlignment = Alignment.Center) {
                Text(w?.name?.take(1) ?: "D", color = Color.White, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title ?: (w?.name ?: "DevNex"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(w?.roleName ?: "", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
            }
            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(18.dp), tint = LocalNx.current.mute)
        }
        Spacer(Modifier.weight(1f))
        if (vm.offline || pending > 0) {
            Pill(if (vm.offline) "Offline" else "Syncing $pending", if (vm.offline) Nx.Amber else Nx.Cyan)
            Spacer(Modifier.width(6.dp))
        }
        IconButton({ nav.go(Routes.SEARCH) }) { Icon(Icons.Rounded.Search, "Search") }
        IconButton(nav.notifications) {
            val unread = notes.count { !it.read }
            BadgedBox(badge = { if (unread > 0) Badge(containerColor = Nx.Magenta) { Text("$unread") } }) {
                Icon(Icons.Rounded.NotificationsNone, "Notifications")
            }
        }
    }
}

// ============================================================== HOME
@Composable
fun HomeTab(vm: MainViewModel, nav: MainNav) {
    val user by vm.user.collectAsStateWithLifecycle()
    val w by vm.workspace.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val stats = vm.stats
    val me = user?.id

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    val needsMe = remember(tasks, w, me) {
        tasks.filter { t ->
            (t.assignedTo == me && t.status in setOf(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.CHANGES_REQUESTED, TaskStatus.BLOCKED)) ||
            (t.status == TaskStatus.SUBMITTED && w?.can(Perm.TASK_APPROVE) == true && t.assignedTo != me &&
                (w?.roleRank ?: 0) >= rankOf(t.currentStageRole))
        }.take(5)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { WorkspaceHeader(vm, nav) }
        item { ErrorBanner(vm.error) { vm.error = null } }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("$greet,", style = MaterialTheme.typography.titleMedium, color = LocalNx.current.mute)
                Text(user?.fullName?.substringBefore(' ') ?: "", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                Text(if (stats?.scope == "company") "Company overview" else "Your overview", style = MaterialTheme.typography.labelSmall, color = Nx.Cyan)
            }
        }
        item {
            if (stats == null) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBox(Modifier.weight(1f).height(170.dp)); SkeletonBox(Modifier.weight(1f).height(170.dp))
                }
            } else PulsePanel(stats)
        }
        item {
            SectionHeader("Needs your action", trailing = { Text("${needsMe.size}", style = MaterialTheme.typography.labelSmall, color = Nx.Cyan) })
        }
        if (needsMe.isEmpty()) item {
            GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DoneAll, null, tint = Nx.Lime); Spacer(Modifier.width(12.dp))
                    Text("You're all caught up.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(needsMe, key = { "n-" + it.id }) { t ->
            TaskCard(t, vm, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { nav.openTask(t.id) }
        }
        if (w?.can(Perm.REPORT_COMPANY) == true || w?.can(Perm.REPORT_DEPARTMENT) == true) {
            item { SectionHeader("Departments") }
            item { DepartmentProgress(vm, tasks) }
        }
        val upcoming = tasks.filter { it.dueAt != null && !it.status.isDone && it.status != TaskStatus.CANCELLED }
            .sortedBy { it.dueAt }.take(4)
        if (upcoming.isNotEmpty()) {
            item { SectionHeader("Deadlines") }
            items(upcoming, key = { "d-" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { nav.openTask(t.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (t.isOverdue) Nx.Red else t.priority.color()))
                    Spacer(Modifier.width(12.dp))
                    Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(t.dueAt), style = MaterialTheme.typography.labelSmall, color = if (t.isOverdue) Nx.Red else LocalNx.current.mute)
                }
            }
        }
        item {
            Text("Powered by Devanshu", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute.copy(alpha = .5f),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

fun rankOf(roleKey: String?) = when (roleKey) { "owner" -> 100; "admin" -> 90; "head" -> 70; "lead" -> 50; "member" -> 10; else -> 100 }

@Composable
private fun PulsePanel(s: DashboardStats) {
    val total = s.doneWeek + s.active
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.weight(1f).height(186.dp), accent = Nx.Violet) {
            Text("This week", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
            Spacer(Modifier.height(8.dp))
            PulseRing(if (total == 0) 0f else s.doneWeek.toFloat() / total, Modifier.fillMaxWidth().weight(1f),
                sub = "${s.doneWeek} done")
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Active", s.active, Nx.Violet, Modifier.weight(1f))
                StatTile("Overdue", s.overdue, if (s.overdue > 0) Nx.Red else Nx.Mute, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Review", s.awaitingReview, Nx.Amber, Modifier.weight(1f))
                StatTile("Pool", s.openPool, Nx.Cyan, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: Color, modifier: Modifier) {
    GlassCard(modifier.height(87.dp), padding = 12.dp) {
        CountUp(value, MaterialTheme.typography.headlineSmall, color)
        Spacer(Modifier.weight(1f))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
    }
}

@Composable
private fun DepartmentProgress(vm: MainViewModel, tasks: List<Task>) {
    val rows = vm.departments.mapNotNull { d ->
        val ts = tasks.filter { it.departmentId == d.id && it.status != TaskStatus.CANCELLED }
        if (ts.isEmpty()) null else Triple(d.name, ts.count { it.status.isDone }, ts.size)
    }
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        if (rows.isEmpty()) Text("No department work yet.", color = LocalNx.current.mute)
        rows.forEachIndexed { i, (name, done, total) ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("$done/$total", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute) }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = listOf(Nx.Violet, Nx.Cyan, Nx.Magenta, Nx.Lime, Nx.Amber)[i % 5], trackColor = LocalNx.current.line)
        }
    }
}

// ============================================================== MORE
@Composable
fun MoreTab(vm: MainViewModel, nav: MainNav) {
    val user by vm.user.collectAsStateWithLifecycle()
    val w by vm.workspace.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { WorkspaceHeader(vm, nav, "More") }
        item {
            GlassCard(Modifier.padding(16.dp).fillMaxWidth(), accent = Nx.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user?.fullName ?: "?", 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user?.fullName ?: "", style = MaterialTheme.typography.titleMedium)
                        Text(user?.email ?: "", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(w?.roleName ?: "", Nx.VioletSoft)
                            if (user?.isPlatformAdmin == true) Pill("Platform", Nx.Cyan)
                            if (vm.isDemo) Pill("Demo", Nx.Amber)
                        }
                    }
                }
            }
        }
        item { SectionHeader("Work") }
        if (w?.can(Perm.TASK_APPROVE) == true)
            item { MoreRow(Icons.Rounded.AssignmentTurnedIn, "Approvals", "Submitted work waiting for your decision", { nav.go(Routes.APPROVALS) }) }
        item { MoreRow(Icons.Rounded.DateRange, "Calendar", "Deadlines, events and publishing by day", { nav.go(Routes.CALENDAR) }) }
        item { MoreRow(Icons.Rounded.Search, "Search", "Tasks, people, skills and events", { nav.go(Routes.SEARCH) }) }
        item { MoreRow(Icons.Rounded.Groups, "Team & workload", "Who has capacity right now", nav.team) }
        if (w?.can(Perm.REPORT_COMPANY) == true || w?.can(Perm.REPORT_DEPARTMENT) == true)
            item { MoreRow(Icons.Rounded.BarChart, "Insights", "Throughput, bottlenecks, workload", nav.insights) }
        if (w?.has(Modules.PUBLISHING) == true || w?.has(Modules.IDEAS) == true || w?.has(Modules.OPS_LOG) == true) item { SectionHeader("Modules") }
        if (w?.has(Modules.PUBLISHING) == true)
            item { MoreRow(Icons.Rounded.Event, "Publishing calendar", "Social and website schedule, published links", { nav.go(Routes.PUBLISHING) }) }
        if (w?.has(Modules.IDEAS) == true)
            item { MoreRow(Icons.Rounded.Lightbulb, "Ideas board", "Future work, reviewed and turned into tasks", { nav.go(Routes.IDEAS) }) }
        if (w?.has(Modules.OPS_LOG) == true)
            item { MoreRow(Icons.Rounded.History, "Operations log", "Decisions, incidents and handovers", { nav.go(Routes.OPS_LOG) }) }
        if (w?.can(Perm.MEMBER_INVITE) == true || w?.can(Perm.COMPANY_MANAGE) == true || w?.can(Perm.AUDIT_VIEW) == true) {
            item { SectionHeader("Company administration") }
            if (w?.can(Perm.MEMBER_INVITE) == true || w?.can(Perm.MEMBER_MANAGE) == true)
                item { MoreRow(Icons.Rounded.SupervisorAccount, "Members", "Roles, departments and access", { nav.go(Routes.MEMBERS) }) }
            if (w?.can(Perm.MEMBER_INVITE) == true)
                item { MoreRow(Icons.Rounded.PersonAdd, "Invite people & modules", "New invitations, optional modules", nav.admin) }
            if (w?.can(Perm.MEMBER_INVITE) == true)
                item { MoreRow(Icons.Rounded.MailOutline, "Invitations", "Pending, accepted and revoked", { nav.go(Routes.INVITATIONS) }) }
            if (w?.can(Perm.DEPARTMENT_MANAGE) == true)
                item { MoreRow(Icons.Rounded.AccountTree, "Departments", "Create and organise teams", { nav.go(Routes.DEPARTMENTS) }) }
            if (w?.can(Perm.COMPANY_MANAGE) == true)
                item { MoreRow(Icons.Rounded.Business, "Company settings", "Branding, visibility, two-step verification", { nav.go(Routes.COMPANY_SETTINGS) }) }
            if (w?.can(Perm.AUDIT_VIEW) == true)
                item { MoreRow(Icons.Rounded.Security, "Audit log", "Every sensitive action on record", { nav.go(Routes.AUDIT) }) }
        }
        item { SectionHeader("Account") }
        item { MoreRow(Icons.Rounded.Person, "My profile", "Name, title, availability, skills", { nav.go(Routes.PROFILE) }) }
        item { MoreRow(Icons.Rounded.SwapHoriz, "Switch company", "One account, every workspace", nav.switchWorkspace) }
        item { MoreRow(Icons.Rounded.NotificationsNone, "Notifications", "Assignments, reviews, mentions", nav.notifications) }
        if (user?.isPlatformAdmin == true) {
            item { SectionHeader("Platform") }
            item { MoreRow(Icons.Rounded.Public, "Platform console", "Companies, status, usage", nav.platform, Nx.Cyan) }
        }
        item { MoreRow(Icons.Rounded.Tune, "Settings", "Theme, sync, security, about", nav.settings) }
        item { MoreRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", "Clears this device's cache", { vm.signOut(nav.signedOut) }, Nx.Red) }
    }
}

@Composable
fun MoreRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, tint: Color = Nx.VioletSoft) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LocalNx.current.mute)
    }
}
