package com.devanshu.devnex.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.PlatformRepository
import com.devanshu.devnex.domain.repository.TeamRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val workspaces: WorkspaceRepository,
    private val team: TeamRepository,
) : ViewModel() {
    val workspace = workspaces.active
    var departments by mutableStateOf<List<Department>>(emptyList()); private set
    var lastInvite by mutableStateOf<Pair<String, String>?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    init { viewModelScope.launch { workspace.value?.let { w -> runCatching { departments = team.departments(w.companyId) } } } }

    fun invite(email: String, role: String, dept: String?) = viewModelScope.launch {
        busy = true; error = null
        try { lastInvite = email to workspaces.invite(email, role, dept) } catch (e: Exception) { error = e.message } finally { busy = false }
    }

    fun toggleModule(module: String, on: Boolean) = viewModelScope.launch {
        runCatching { workspaces.setModule(module, on) }.onFailure { error = it.message }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(onBack: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val w by vm.workspace.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    var dept by remember { mutableStateOf<String?>(null) }
    val roles = listOf("admin" to 90, "head" to 70, "lead" to 50, "member" to 10).filter { (_, r) -> (w?.roleRank ?: 0) >= 100 || r < (w?.roleRank ?: 0) }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Company admin") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ErrorBanner(vm.error) { vm.error = null } }
                if (w?.can(Perm.MEMBER_INVITE) == true) item {
                    GlassCard(Modifier.fillMaxWidth(), accent = Nx.Violet) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PersonAdd, null, tint = Nx.Violet); Spacer(Modifier.width(10.dp))
                            Text("Invite a member", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Their work email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(14.dp), colors = nxFieldColors())
                        Spacer(Modifier.height(10.dp))
                        Text("ROLE", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            roles.forEach { (k, _) -> FilterChip(role == k, { role = k }, label = { Text(k.replaceFirstChar { it.uppercase() }) }) }
                        }
                        Text("DEPARTMENT", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(dept == null, { dept = null }, label = { Text("None") })
                            vm.departments.forEach { d -> FilterChip(dept == d.id, { dept = d.id }, label = { Text(d.name) }) }
                        }
                        Spacer(Modifier.height(12.dp))
                        GradientButton("Create invitation", Modifier.fillMaxWidth(), enabled = email.contains('@'), loading = vm.busy) {
                            vm.invite(email.trim(), role, dept)
                        }
                        vm.lastInvite?.let { (who, code) ->
                            Spacer(Modifier.height(12.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nx.Lime.copy(alpha = .12f)).padding(12.dp)) {
                                Text("Invitation for $who", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(code, fontFamily = Mono, style = MaterialTheme.typography.titleLarge, color = Nx.Lime, modifier = Modifier.weight(1f))
                                    IconButton({ clipboard.setText(AnnotatedString(code)) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                                }
                                Text("Shown once. Works only for that email, expires in 7 days. Deep link: devnex://join?token=$code",
                                    style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                            }
                        }
                    }
                }
                if (w?.can(Perm.MODULE_MANAGE) == true) item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Modules", style = MaterialTheme.typography.titleMedium)
                        Text("Industry features stay out of the way unless you switch them on.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        listOf(Modules.COVERAGE to "Coverage Desk (events, reporters, editorial score)",
                               Modules.PUBLISHING to "Publishing calendar & records",
                               Modules.IDEAS to "Ideas board", Modules.OPS_LOG to "Operations log").forEach { (k, l) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(l, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(w?.has(k) == true, { vm.toggleModule(k, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Nx.Violet))
                            }
                        }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Departments", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.departments.forEach { Pill(it.name, Nx.Cyan) }
                        }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Security, null, tint = Nx.Lime); Spacer(Modifier.width(10.dp))
                            Text("Security posture", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "Row Level Security on every table — enforced by the database",
                            "Two-step verification required for owners and admins",
                            "Nobody can change their own role or approve their own work",
                            "Every claim, approval, role change and deletion is audited",
                            "Platform owner sees counts only — content needs your time-limited consent",
                            "Local cache encrypted with a key held in Android Keystore",
                        ).forEach { Row(Modifier.padding(vertical = 3.dp)) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Nx.Lime); Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
    }
}

// ============================================================== Platform console
@HiltViewModel
class PlatformViewModel @Inject constructor(private val platform: PlatformRepository) : ViewModel() {
    var companies by mutableStateOf<List<CompanyOverview>?>(null); private set
    var error by mutableStateOf<String?>(null)
    init { load() }
    fun load() = viewModelScope.launch { runCatching { companies = platform.overview() }.onFailure { error = it.message; companies = emptyList() } }
    fun setStatus(id: String, status: String) = viewModelScope.launch {
        runCatching { platform.setStatus(id, status) }.onFailure { error = it.message }; load()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformScreen(onBack: () -> Unit, vm: PlatformViewModel = hiltViewModel()) {
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Column { Text("Platform console"); Text("DEVNEX · OWNER", style = MaterialTheme.typography.labelSmall, color = Nx.Cyan) } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            val list = vm.companies
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ErrorBanner(vm.error) { vm.error = null } }
                if (list == null) item { SkeletonList(3) } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("Companies" to list.size, "Members" to list.sumOf { it.members }, "Tasks" to list.sumOf { it.tasks }).forEach { (l, v) ->
                                GlassCard(Modifier.weight(1f), padding = 12.dp) {
                                    CountUp(v, MaterialTheme.typography.headlineSmall, Nx.Cyan)
                                    Text(l.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nx.Cyan.copy(alpha = .1f)).padding(12.dp)) {
                            Icon(Icons.Rounded.Lock, null, tint = Nx.Cyan, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("You see metadata and counts only. Company tasks and messages stay private unless the company grants time-limited support access.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(list, key = { it.companyId }) { c ->
                        GlassCard(Modifier.fillMaxWidth(), accent = if (c.status == "active") null else Nx.Red) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${c.industry} · ${c.plan} plan · ${c.members} members · ${c.tasks} tasks", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                                }
                                Pill(c.status.replaceFirstChar { it.uppercase() }, if (c.status == "active") Nx.Lime else Nx.Red)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (c.status == "active") OutlinedButton({ vm.setStatus(c.companyId, "suspended") }) { Text("Suspend") }
                                else OutlinedButton({ vm.setStatus(c.companyId, "active") }) { Text("Reactivate") }
                            }
                        }
                    }
                }
            }
        }
    }
}
