package com.devanshu.devnex.feature.admin

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.AdminRepository
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.TeamRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AdminV2ViewModel @Inject constructor(
    private val admin: AdminRepository,
    private val team: TeamRepository,
    private val workspaces: WorkspaceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val workspace get() = workspaces.active.value
    val me = auth.session.value?.id
    private val cid get() = workspace?.companyId.orEmpty()
    var members by mutableStateOf<List<Member>?>(null); private set
    var departments by mutableStateOf<List<Department>>(emptyList()); private set
    var settings by mutableStateOf<CompanySettings?>(null); private set
    var audit by mutableStateOf<List<AuditEntry>?>(null); private set
    var invites by mutableStateOf<List<Invitation>?>(null); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    fun loadTeam() = viewModelScope.launch {
        runCatching { members = team.members(cid); departments = team.departments(cid) }.onFailure { message = it.message; members = emptyList() }
    }
    fun loadSettings() = viewModelScope.launch { runCatching { settings = admin.settings(cid) }.onFailure { message = it.message } }
    fun loadAudit() = viewModelScope.launch { runCatching { audit = admin.audit(cid) }.onFailure { message = it.message; audit = emptyList() } }
    fun loadInvites() = viewModelScope.launch { runCatching { invites = admin.invitations(cid) }.onFailure { message = it.message; invites = emptyList() } }

    private fun act(ok: String, after: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block(); message = ok; after() } catch (e: Exception) { message = e.message } finally { busy = false }
        }
    }
    fun setRole(m: Member, role: String) = act("${m.fullName} is now ${role.replaceFirstChar { it.uppercase() }}", { loadTeam() }) { admin.setMemberRole(m.membershipId, role) }
    fun setDept(m: Member, dept: String?) = act("Department updated", { loadTeam() }) { admin.setMemberDepartment(m.membershipId, dept) }
    fun deactivate(m: Member) = act("${m.fullName} no longer has access", { loadTeam() }) { admin.setMemberStatus(m.membershipId, "suspended") }
    fun addDept(name: String) = act("Department created", { loadTeam() }) { admin.createDepartment(cid, name) }
    fun deleteDept(d: Department) = act("Department deleted", { loadTeam() }) { admin.deleteDepartment(d.id) }
    fun saveSettings(s: CompanySettings) = act("Company settings saved", { loadSettings(); viewModelScope.launch { runCatching { workspaces.refresh() } } }) {
        admin.updateSettings(cid, s)
    }
    fun revoke(i: Invitation) = act("Invitation revoked", { loadInvites() }) { admin.revokeInvitation(i.id) }
}

@Composable
private fun SnackFor(vm: AdminV2ViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm.message) { vm.message?.let { host.showSnackbar(it); vm.message = null } }
}

private val roleOptions = listOf("admin" to "Admin", "head" to "Department Head", "lead" to "Team Lead", "member" to "Member")
private fun rankOfRole(k: String) = when (k) { "owner" -> 100; "admin" -> 90; "head" -> 70; "lead" -> 50; else -> 10 }

// ============================================================== Members
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MembersScreen(onBack: () -> Unit, onInvite: () -> Unit, vm: AdminV2ViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadTeam() }
    val host = remember { SnackbarHostState() }; SnackFor(vm, host)
    var selected by remember { mutableStateOf<Member?>(null) }
    var confirmRemove by remember { mutableStateOf<Member?>(null) }
    var query by remember { mutableStateOf("") }
    val myRank = vm.workspace?.roleRank ?: 0
    Scaffold(
        topBar = { NxTopBar("Members", onBack, subtitle = vm.workspace?.name, actions = {
            if (vm.workspace?.can(Perm.MEMBER_INVITE) == true) TextButton(onInvite) { Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Invite") }
        }) },
        snackbarHost = { SnackbarHost(host) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val ms = vm.members
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search people") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
            }
            if (ms == null) item { SkeletonList(4) }
            else items(ms.filter { query.isBlank() || it.fullName.contains(query, true) || it.email.contains(query, true) }, key = { it.userId }) { m ->
                Row(Modifier.fillMaxWidth().clickable(enabled = vm.workspace?.can(Perm.MEMBER_MANAGE) == true && m.userId != vm.me) { selected = m }
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.fullName, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.fullName + if (m.userId == vm.me) " (you)" else "", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(m.title.ifBlank { null }, vm.departments.firstOrNull { it.id == m.departmentId }?.name, m.email).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, maxLines = 1)
                    }
                    Pill(m.roleName, if (m.roleRank >= 90) Nx.Violet else if (m.roleRank >= 50) Nx.Cyan else Nx.Mute)
                }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = LocalNx.current.line)
            }
        }
    }
    selected?.let { m ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.fullName, 44.dp); Spacer(Modifier.width(12.dp))
                    Column { Text(m.fullName, style = MaterialTheme.typography.titleMedium); Text(m.email, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute) }
                }
                Text("Role", style = MaterialTheme.typography.labelLarge)
                val canManage = myRank >= 100 || m.roleRank < myRank
                if (!canManage) Text("You can only manage people below your own role.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roleOptions.filter { (k, _) -> myRank >= 100 || rankOfRole(k) < myRank }.forEach { (k, l) ->
                        FilterChip(m.roleKey == k, { if (canManage) { vm.setRole(m, k); selected = null } }, label = { Text(l) }, enabled = canManage)
                    }
                }
                Text("Department", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(m.departmentId == null, { vm.setDept(m, null); selected = null }, label = { Text("None") })
                    vm.departments.forEach { d -> FilterChip(m.departmentId == d.id, { vm.setDept(m, d.id); selected = null }, label = { Text(d.name) }) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton({ confirmRemove = m; selected = null }, enabled = canManage, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Nx.Red)) { Text("Remove access") }
            }
        }
    }
    confirmRemove?.let { m ->
        AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("Remove ${m.fullName}?") },
            text = { Text("They will lose access to this company immediately. Their past work and history stay on record.") },
            confirmButton = { TextButton({ vm.deactivate(m); confirmRemove = null }, colors = ButtonDefaults.textButtonColors(contentColor = Nx.Red)) { Text("Remove") } },
            dismissButton = { TextButton({ confirmRemove = null }) { Text("Cancel") } })
    }
}

// ============================================================== Departments
@Composable
fun DepartmentsScreen(onBack: () -> Unit, vm: AdminV2ViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadTeam() }
    val host = remember { SnackbarHostState() }; SnackFor(vm, host)
    var name by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Department?>(null) }
    val canEdit = vm.workspace?.can(Perm.DEPARTMENT_MANAGE) == true
    Scaffold(topBar = { NxTopBar("Departments", onBack) }, snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (canEdit) item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), placeholder = { Text("New department name") }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
                    Spacer(Modifier.width(10.dp))
                    Button({ vm.addDept(name); name = "" }, enabled = name.isNotBlank(), shape = RoundedCornerShape(12.dp)) { Text("Add") }
                }
            }
            items(vm.departments, key = { it.id }) { d ->
                val people = vm.members?.filter { it.departmentId == d.id }.orEmpty()
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Nx.Violet.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Groups, null, tint = Nx.Violet)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (people.isEmpty()) "No members" else people.joinToString { it.fullName.substringBefore(' ') },
                            style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, maxLines = 1)
                    }
                    if (canEdit) IconButton({ confirm = d }) { Icon(Icons.Rounded.Delete, "Delete ${d.name}", tint = LocalNx.current.mute) }
                }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = LocalNx.current.line)
            }
        }
    }
    confirm?.let { d ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete ${d.name}?") },
            text = { Text("Members of this department will have no department until you move them. Tasks keep their history.") },
            confirmButton = { TextButton({ vm.deleteDept(d); confirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = Nx.Red)) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

// ============================================================== Company settings
private val brandSwatches = listOf("#1D4ED8", "#0F766E", "#7C3AED", "#B45309", "#BE123C", "#0369A1", "#334155", "#15803D")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanySettingsScreen(onBack: () -> Unit, vm: AdminV2ViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadSettings() }
    val host = remember { SnackbarHostState() }; SnackFor(vm, host)
    val s = vm.settings
    Scaffold(topBar = { NxTopBar("Company settings", onBack) }, snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        if (s == null) { Box(Modifier.padding(pad)) { SkeletonList(3) }; return@Scaffold }
        var name by remember(s) { mutableStateOf(s.name) }
        var brand by remember(s) { mutableStateOf(s.brandColor.uppercase()) }
        var visibility by remember(s) { mutableStateOf(s.memberVisibility) }
        var mfa by remember(s) { mutableIntStateOf(s.mfaRequiredRank) }
        val canEdit = vm.workspace?.can(Perm.COMPANY_MANAGE) == true
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Branding", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Company name") }, singleLine = true, enabled = canEdit, colors = nxFieldColors())
                    Spacer(Modifier.height(12.dp))
                    Text("Brand colour", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        brandSwatches.forEach { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(Modifier.size(38.dp).clip(CircleShape).background(c)
                                .border(if (brand == hex) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                .clickable(enabled = canEdit) { brand = hex })
                        }
                    }
                    Text("Shown on the company logo and workspace switcher. DevNex keeps its own neutral interface for readability.",
                        style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Task visibility for members", style = MaterialTheme.typography.titleMedium)
                    Text("Heads and admins always see every task.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    listOf("own" to "Only their own tasks", "department" to "Their department's tasks (recommended)", "company" to "All company tasks").forEach { (k, l) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { visibility = k }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(visibility == k, { visibility = k }, enabled = canEdit); Text(l, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Two-step verification", style = MaterialTheme.typography.titleMedium)
                    Text("Who must confirm sign-in with an authenticator app.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    listOf(90 to "Owner and admins (recommended)", 70 to "Also department heads", 50 to "Also team leads", 0 to "Everyone").forEach { (k, l) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { mfa = k }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mfa == k, { mfa = k }, enabled = canEdit); Text(l, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (canEdit) item {
                GradientButton("Save settings", Modifier.fillMaxWidth(), loading = vm.busy) { vm.saveSettings(CompanySettings(name, brand, visibility, mfa)) }
            } else item { Text("Only the owner or admins can change these settings.", color = LocalNx.current.mute) }
        }
    }
}

// ============================================================== Audit log
@Composable
fun AuditScreen(onBack: () -> Unit, vm: AdminV2ViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadAudit() }
    var query by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }
    Scaffold(topBar = { NxTopBar("Audit log", onBack, subtitle = "Every sensitive action, who did it and when") }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search by person, action or item") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = nxFieldColors())
            }
            val list = vm.audit
            if (list == null) item { SkeletonList(4) }
            else {
                val shown = list.filter { query.isBlank() || "${it.actorName} ${it.action} ${it.detail}".contains(query, true) }
                if (shown.isEmpty()) item { EmptyState(Icons.Rounded.Security, "No audit entries", vm.message ?: "Claims, approvals, role changes and deletions appear here.") }
                items(shown, key = { it.id }) { a ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.action.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(fmt.format(a.at), style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        }
                        Text("${a.actorName} · ${a.detail}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalNx.current.line)
                }
            }
        }
    }
}

// ============================================================== Invitations
@Composable
fun InvitationsScreen(onBack: () -> Unit, onInvite: () -> Unit, vm: AdminV2ViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadInvites() }
    val host = remember { SnackbarHostState() }; SnackFor(vm, host)
    val fmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    Scaffold(topBar = { NxTopBar("Invitations", onBack, actions = { TextButton(onInvite) { Text("New invite") } }) },
        snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
            val list = vm.invites
            if (list == null) item { SkeletonList(3) }
            else if (list.isEmpty()) item { EmptyState(Icons.Rounded.MailOutline, "No invitations yet", "Invite people from Company admin. Each code works once, for one email, for 7 days.") }
            else items(list, key = { it.id }) { i ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.email, style = MaterialTheme.typography.titleSmall)
                        Text("${i.roleKey.replaceFirstChar { it.uppercase() }} · expires ${fmt.format(i.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                    val c = when (i.status) { "pending" -> Nx.Amber; "accepted" -> Nx.Lime; else -> Nx.Mute }
                    Pill(i.status.replaceFirstChar { it.uppercase() }, c)
                    if (i.status == "pending") TextButton({ vm.revoke(i) }, colors = ButtonDefaults.textButtonColors(contentColor = Nx.Red)) { Text("Revoke") }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalNx.current.line)
            }
        }
    }
}
