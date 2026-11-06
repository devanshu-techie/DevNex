package com.devanshu.devnex.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.BuildConfig
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.NotificationRepository
import com.devanshu.devnex.domain.repository.TaskRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import com.devanshu.devnex.feature.splash.DevNexSplashAnimation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide preferences (theme). Observed by MainActivity. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("devnex_ui", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", "LIGHT")!!) }.getOrDefault(ThemeMode.LIGHT))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(m: ThemeMode) { prefs.edit().putString("theme", m.name).apply(); _theme.value = m }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val prefs: AppPrefs,
    auth: AuthRepository,
    tasks: TaskRepository,
) : ViewModel() {
    val isDemo = auth.isDemo
    val pending = tasks.pendingSyncCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val theme by vm.prefs.theme.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var replay by remember { mutableIntStateOf(0) }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Appearance", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { m -> FilterChip(theme == m, { vm.prefs.setTheme(m) },
                                label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                leadingIcon = { Icon(when (m) { ThemeMode.DARK -> Icons.Rounded.DarkMode; ThemeMode.LIGHT -> Icons.Rounded.LightMode
                                    ThemeMode.SYSTEM -> Icons.Rounded.Settings }, null, Modifier.size(16.dp)) }) }
                        }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Sync & backend", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Sync, null, tint = Nx.Cyan); Spacer(Modifier.width(10.dp))
                            Text(if (pending == 0) "Everything is synced" else "$pending change(s) waiting for a connection", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Storage, null, tint = if (vm.isDemo) Nx.Amber else Nx.Lime); Spacer(Modifier.width(10.dp))
                            Text(if (vm.isDemo) "Demo backend (in-memory). Add Supabase keys to go live." else "Supabase · ${BuildConfig.SUPABASE_URL.removePrefix("https://")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Offline: you can read everything cached and draft messages, checklist ticks and comments. Claims and approvals need a connection so ownership is never doubled.",
                            style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth().clickable { replay++ }, padding = 0.dp) {
                        key(replay) { DevNexSplashAnimation(Modifier.fillMaxWidth().height(320.dp)) }
                    }
                    Text("Tap to replay · DevNex ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

// ============================================================== Notifications
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(private val repo: NotificationRepository, workspaces: WorkspaceRepository) : ViewModel() {
    val items = workspaces.active.flatMapLatest { w -> if (w == null) flowOf(emptyList()) else repo.observe(w.companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init { viewModelScope.launch { workspaces.active.value?.let { runCatching { repo.refresh(it.companyId) } } } }
    fun read(id: String) = viewModelScope.launch { repo.markRead(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Notifications") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .5f) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (items.isEmpty()) item { EmptyState(Icons.Rounded.NotificationsNone, "All quiet", "You'll hear about assignments, reviews, approvals and mentions — nothing else.") }
                items(items, key = { it.id }) { n ->
                    Row(Modifier.fillMaxWidth().clickable { vm.read(n.id); n.taskId?.let(onOpenTask) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val (icon, color) = when (n.kind) {
                            "assignment" -> Icons.Rounded.AssignmentInd to Nx.Violet
                            "review" -> Icons.Rounded.RateReview to Nx.Amber
                            "approved" -> Icons.Rounded.Verified to Nx.Lime
                            "changes" -> Icons.Rounded.Replay to Nx.Magenta
                            "claim" -> Icons.Rounded.PanTool to Nx.Cyan
                            else -> Icons.Rounded.Notifications to Nx.VioletSoft
                        }
                        Icon(icon, null, tint = color)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.title, style = MaterialTheme.typography.titleSmall, color = if (n.read) LocalNx.current.mute else MaterialTheme.colorScheme.onBackground)
                            Text(n.body, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute, maxLines = 2)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(relativeTime(n.at), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                            if (!n.read) Pill("new", Nx.Cyan)
                        }
                    }
                }
            }
        }
    }
}
