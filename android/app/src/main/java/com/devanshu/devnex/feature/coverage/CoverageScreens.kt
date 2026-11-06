package com.devanshu.devnex.feature.coverage

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.CoverageRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import com.devanshu.devnex.feature.dashboard.MainNav
import com.devanshu.devnex.feature.dashboard.MainViewModel
import com.devanshu.devnex.feature.dashboard.WorkspaceHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CoverageViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: CoverageRepository,
    workspaces: WorkspaceRepository,
) : ViewModel() {
    val workspace = workspaces.active.value
    val eventId: String? = saved["eventId"]
    val eventName: String = saved["name"] ?: ""
    var events by mutableStateOf<List<CoverageEvent>?>(null); private set
    var submissions by mutableStateOf<List<CoverageSubmission>?>(null); private set
    var error by mutableStateOf<String?>(null)

    init { load() }

    fun load() = viewModelScope.launch {
        val w = workspace ?: return@launch
        runCatching {
            if (eventId == null) { events = repo.events(w.companyId) } else { submissions = repo.submissions(eventId) }
            Unit
        }.onFailure { error = it.message }
    }

    fun createEvent(name: String, sport: String, venue: String, daysFromNow: Int) = viewModelScope.launch {
        val w = workspace ?: return@launch
        runCatching { repo.createEvent(w.companyId, name, sport, venue, System.currentTimeMillis() + daysFromNow * 86_400_000L, Priority.NORMAL) }
            .onFailure { error = it.message }
        load()
    }

    fun submit(athlete: String, result: String, quotes: String, angle: String, links: String) = viewModelScope.launch {
        val w = workspace ?: return@launch
        runCatching { repo.submit(w.companyId, eventId!!, athlete, result, quotes, angle, links.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }) }
            .onFailure { error = it.message }
        load()
    }

    fun setStatus(id: String, status: String) = viewModelScope.launch {
        runCatching { repo.setEditorialStatus(id, status) }.onFailure { error = it.message }; load()
    }
}

@Composable
fun CoverageTab(vm: MainViewModel, nav: MainNav, cvm: CoverageViewModel = hiltViewModel()) {
    val w by vm.workspace.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { WorkspaceHeader(vm, nav, "Coverage Desk") }
            item { ErrorBanner(cvm.error) { cvm.error = null } }
            val evs = cvm.events
            if (evs == null) item { SkeletonList(3) }
            else if (evs.isEmpty()) item { EmptyState(Icons.Rounded.EmojiEvents, "No events yet", "Register an event, then reporters submit results, quotes and links.") }
            else items(evs, key = { it.id }) { e ->
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = { nav.openEvent(e.id, e.name) },
                    accent = if (e.status == "live") Nx.Magenta else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.sport.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute, modifier = Modifier.weight(1f))
                        Pill(e.status.replaceFirstChar { it.uppercase() }, when (e.status) { "live" -> Nx.Magenta; "covered" -> Nx.Lime; else -> Nx.Cyan })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("${fmt.format(e.eventDate)} · ${e.venue} · ${e.submissions} submissions", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                }
            }
        }
        if (w?.can(Perm.COVERAGE_MANAGE) == true) ExtendedFloatingActionButton(
            onClick = { showNew = true }, containerColor = Nx.Magenta, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Register event") })
    }
    if (showNew) {
        var name by remember { mutableStateOf("") }; var sport by remember { mutableStateOf("Athletics") }
        var venue by remember { mutableStateOf("") }; var days by remember { mutableStateOf("0") }
        AlertDialog(onDismissRequest = { showNew = false }, title = { Text("Register event") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Event name") }, singleLine = true, colors = nxFieldColors())
                OutlinedTextField(sport, { sport = it }, label = { Text("Sport / discipline") }, singleLine = true, colors = nxFieldColors())
                OutlinedTextField(venue, { venue = it }, label = { Text("Venue") }, singleLine = true, colors = nxFieldColors())
                OutlinedTextField(days, { days = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Days from today") }, singleLine = true, colors = nxFieldColors())
            } },
            confirmButton = { TextButton({ cvm.createEvent(name, sport, venue, days.toIntOrNull() ?: 0); showNew = false }, enabled = name.isNotBlank()) { Text("Register") } },
            dismissButton = { TextButton({ showNew = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(onBack: () -> Unit, vm: CoverageViewModel = hiltViewModel()) {
    var showForm by remember { mutableStateOf(false) }
    val canManage = vm.workspace?.can(Perm.COVERAGE_MANAGE) == true
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text(vm.eventName, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) },
        floatingActionButton = { if (vm.workspace?.can(Perm.COVERAGE_SUBMIT) == true)
            ExtendedFloatingActionButton(onClick = { showForm = true }, containerColor = Nx.Violet, contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Submit report") }) },
    ) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Ranked by editorial priority  P = 0.25F + 0.25I + 0.20U + 0.15A + 0.10R + 0.05V. A recommendation, not a decision.",
                        style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                }
                item { ErrorBanner(vm.error) { vm.error = null } }
                val subs = vm.submissions
                if (subs == null) item { SkeletonList(2) }
                else if (subs.isEmpty()) item { EmptyState(Icons.Rounded.Edit, "No submissions yet", "Reporters' results, quotes and media links appear here.") }
                else items(subs, key = { it.id }) { s -> SubmissionCard(s, canManage) { st -> vm.setStatus(s.id, st) } }
            }
        }
    }
    if (showForm) SubmitSheet({ showForm = false }) { a, r, q, an, l -> vm.submit(a, r, q, an, l); showForm = false }
}

@Composable
private fun SubmissionCard(s: CoverageSubmission, canManage: Boolean, onStatus: (String) -> Unit) {
    val score = s.editorialScore()
    GlassCard(Modifier.fillMaxWidth(), accent = if (score >= 4) Nx.Lime else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.athlete.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                Text("${s.result} · by ${s.reporterName}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(String.format(Locale.US, "%.2f", score), fontFamily = Mono, style = MaterialTheme.typography.headlineSmall,
                    color = if (score >= 4) Nx.Lime else if (score >= 3) Nx.Cyan else LocalNx.current.mute)
                Text("P-SCORE", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf('F', 'I', 'U', 'A', 'R', 'V').forEach { k ->
                val v = s.scores[k] ?: 0
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(LocalNx.current.line), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(v / 5f).background(Nx.Signature))
                    }
                    Text("$k$v", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                }
            }
        }
        if (s.quotes.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(s.quotes, style = MaterialTheme.typography.bodyMedium) }
        if (s.newsAngle.isNotBlank()) { Spacer(Modifier.height(6.dp)); Pill("Angle: ${s.newsAngle}", Nx.Magenta) }
        s.mediaLinks.forEach { Text("🔗 $it", style = MaterialTheme.typography.bodySmall, color = Nx.Cyan) }
        Spacer(Modifier.height(10.dp))
        if (canManage) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("selected" to "Select", "high_priority" to "High priority", "waiting_info" to "Need info", "scheduled" to "Later", "rejected" to "Reject")
                .forEach { (k, l) -> FilterChip(s.editorialStatus == k, { onStatus(k) }, label = { Text(l) }) }
        } else Pill(s.editorialStatus.replace('_', ' '), Nx.VioletSoft)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubmitSheet(onDismiss: () -> Unit, onSubmit: (String, String, String, String, String) -> Unit) {
    var athlete by remember { mutableStateOf("") }; var result by remember { mutableStateOf("") }
    var quotes by remember { mutableStateOf("") }; var angle by remember { mutableStateOf("") }; var links by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reporter submission", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(athlete, { athlete = it }, Modifier.fillMaxWidth(), label = { Text("Athlete / team") }, colors = nxFieldColors())
            OutlinedTextField(result, { result = it }, Modifier.fillMaxWidth(), label = { Text("Result (position, time, score)") }, colors = nxFieldColors())
            OutlinedTextField(quotes, { quotes = it }, Modifier.fillMaxWidth(), label = { Text("Key quotes") }, minLines = 2, colors = nxFieldColors())
            OutlinedTextField(angle, { angle = it }, Modifier.fillMaxWidth(), label = { Text("News angle (record, upset, comeback…)") }, colors = nxFieldColors())
            OutlinedTextField(links, { links = it }, Modifier.fillMaxWidth(), label = { Text("Media links (drive folders, one per line)") }, minLines = 2, colors = nxFieldColors(),
                supportingText = { Text("Links only — photos and video stay in your existing storage.") })
            GradientButton("Submit to editorial", Modifier.fillMaxWidth(), enabled = athlete.isNotBlank() || result.isNotBlank()) {
                onSubmit(athlete, result, quotes, angle, links)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
