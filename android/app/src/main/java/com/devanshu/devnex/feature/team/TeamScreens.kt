package com.devanshu.devnex.feature.team

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.*
import com.devanshu.devnex.domain.repository.TaskRepository
import com.devanshu.devnex.domain.repository.TeamRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeamViewModel @Inject constructor(
    private val team: TeamRepository,
    private val tasks: TaskRepository,
    workspaces: WorkspaceRepository,
) : ViewModel() {
    val workspace = workspaces.active.value
    var members by mutableStateOf<List<Member>?>(null); private set
    var departments by mutableStateOf<List<Department>>(emptyList()); private set
    var allTasks by mutableStateOf<List<Task>>(emptyList()); private set

    init {
        viewModelScope.launch {
            val w = workspace ?: return@launch
            runCatching {
                departments = team.departments(w.companyId)
                allTasks = tasks.observeTasks(w.companyId).first()
                members = team.members(w.companyId).map { m ->
                    m.copy(activeTasks = allTasks.count { it.assignedTo == m.userId && it.status.isActive })
                }
            }.onFailure { members = emptyList() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(onBack: () -> Unit, vm: TeamViewModel = hiltViewModel()) {
    var dept by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Team & workload") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            val ms = vm.members
            if (ms == null) { SkeletonList(); return@AuroraBackground }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(dept == null, { dept = null }, label = { Text("Everyone ${ms.size}") })
                        vm.departments.forEach { d -> FilterChip(dept == d.id, { dept = d.id }, label = { Text(d.name) }) }
                    }
                }
                item {
                    Text("Capacity bar: 5 active tasks = full. Use it to spread work fairly — not to rank people.",
                        style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                items(ms.filter { dept == null || it.departmentId == dept }, key = { it.userId }) { m ->
                    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                Avatar(m.fullName, 44.dp)
                                Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp)
                                    .clip(CircleShape).background(when (m.availability) { "available" -> Nx.Lime; "busy" -> Nx.Amber; else -> Nx.Mute }))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.fullName, style = MaterialTheme.typography.titleSmall)
                                Text("${m.title.ifBlank { m.roleName }} · ${vm.departments.firstOrNull { it.id == m.departmentId }?.name ?: "All departments"}",
                                    style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                                Spacer(Modifier.height(8.dp))
                                val load = (m.activeTasks / 5f).coerceAtMost(1f)
                                LinearProgressIndicator(progress = { load }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = if (load >= .8f) Nx.Red else if (load >= .5f) Nx.Amber else Nx.Cyan, trackColor = LocalNx.current.line)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${m.activeTasks}", fontFamily = Mono, style = MaterialTheme.typography.titleLarge)
                                Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                            }
                        }
                        if (m.skills.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.skills.take(4).forEach { Pill(it, Nx.VioletSoft) } }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onBack: () -> Unit, vm: TeamViewModel = hiltViewModel()) {
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Insights") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            val ts = vm.allTasks
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val done = ts.count { it.status.isDone }
                    val rework = ts.count { it.status == TaskStatus.CHANGES_REQUESTED }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi("Completed", "$done", Nx.Lime, Modifier.weight(1f))
                        Kpi("Overdue", "${ts.count { it.isOverdue }}", Nx.Red, Modifier.weight(1f))
                        Kpi("Rework", if (ts.isEmpty()) "0%" else "${rework * 100 / ts.size}%", Nx.Magenta, Modifier.weight(1f))
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("WORK BY STAGE", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        Spacer(Modifier.height(12.dp))
                        val stages = TaskStatus.entries.filter { it != TaskStatus.CANCELLED }.map { it to ts.count { t -> t.status == it } }.filter { it.second > 0 }
                        BarChart(stages.map { it.first.label.substringBefore(' ') to it.second }, stages.map { it.first.color() })
                        Spacer(Modifier.height(6.dp))
                        Text("Tall amber = approvals are the bottleneck. Tall red = work is blocked.", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("DEPARTMENT LOAD", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        Spacer(Modifier.height(12.dp))
                        val rows = vm.departments.map { d -> d.name to ts.count { it.departmentId == d.id && it.status.isActive } }.filter { it.second > 0 }
                        BarChart(rows, listOf(Nx.Violet, Nx.Cyan, Nx.Magenta, Nx.Lime, Nx.Amber))
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("WORKLOAD BY PERSON", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                        Spacer(Modifier.height(12.dp))
                        (vm.members ?: emptyList()).sortedByDescending { it.activeTasks }.take(6).forEach { m ->
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.fullName.substringBefore(' '), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress = { (m.activeTasks / 5f).coerceAtMost(1f) }, Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Nx.Cyan, trackColor = LocalNx.current.line)
                                Text(" ${m.activeTasks}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color, modifier: Modifier) {
    GlassCard(modifier, padding = 12.dp) {
        Text(value, fontFamily = Mono, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
    }
}

@Composable
private fun BarChart(data: List<Pair<String, Int>>, colors: List<Color>) {
    if (data.isEmpty()) { Text("No data yet", color = LocalNx.current.mute); return }
    val max = data.maxOf { it.second }.coerceAtLeast(1)
    val track = LocalNx.current.line
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val gap = 10f
        val w = (size.width - gap * (data.size - 1)) / data.size
        data.forEachIndexed { i, (_, v) ->
            val h = size.height * v / max
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(10f))
            drawRoundRect(colors[i % colors.size], Offset(x, size.height - h), Size(w, h), CornerRadius(10f))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        data.forEach { (l, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$v", fontFamily = Mono, style = MaterialTheme.typography.labelMedium)
                Text(l, style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute, maxLines = 1)
            }
        }
    }
}
