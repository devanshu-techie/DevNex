package com.devanshu.devnex.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.Perm
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.ChatRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import com.devanshu.devnex.feature.dashboard.MainNav
import com.devanshu.devnex.feature.dashboard.MainViewModel
import com.devanshu.devnex.feature.dashboard.WorkspaceHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelsViewModel @Inject constructor(private val chat: ChatRepository, workspaces: WorkspaceRepository) : ViewModel() {
    val channels = workspaces.active.flatMapLatest { w -> if (w == null) flowOf(emptyList()) else chat.observeChannels(w.companyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init { viewModelScope.launch { workspaces.active.value?.let { runCatching { chat.refreshChannels(it.companyId) } } } }
}

@Composable
fun ChannelsTab(vm: MainViewModel, nav: MainNav, cvm: ChannelsViewModel = hiltViewModel()) {
    val channels by cvm.channels.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { WorkspaceHeader(vm, nav, "Messages") }
        item {
            Text("Text-only by design: fast, light, searchable. Share files as links.", style = MaterialTheme.typography.bodySmall,
                color = LocalNx.current.mute, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (channels.isEmpty()) item { SkeletonList(3) }
        items(channels, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().clickable { nav.openChannel(c.id, c.name) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (c.kind == "announcement") Nx.Amber.copy(alpha = .15f) else Nx.Violet.copy(alpha = .15f)),
                    contentAlignment = Alignment.Center) {
                    if (c.kind == "announcement") Icon(Icons.Rounded.Announcement, null, tint = Nx.Amber)
                    else Text("#", style = MaterialTheme.typography.titleLarge, color = Nx.VioletSoft)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(c.lastMessage ?: "No messages yet", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(c.lastAt?.let { relativeTime(it) } ?: "", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
            }
        }
    }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val chat: ChatRepository,
    workspaces: WorkspaceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val channelId: String = checkNotNull(saved["channelId"])
    val channelName: String = saved["name"] ?: "channel"
    val me = auth.session.value?.id
    private val companyId = workspaces.active.value?.companyId.orEmpty()
    val canAnnounce = workspaces.active.value?.can(Perm.CHAT_ANNOUNCE) == true
    val messages = chat.observeMessages(channelId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var loadingOlder by mutableStateOf(false); private set

    init {
        viewModelScope.launch { runCatching { chat.loadMessages(channelId) } }
        // Live only while this conversation is open; cancelled automatically on exit.
        viewModelScope.launch { chat.live(companyId, channelId).collect { } }
    }

    fun send(text: String) = viewModelScope.launch { if (text.isNotBlank()) chat.send(companyId, channelId, text) }
    fun older() {
        val oldest = messages.value.lastOrNull()?.at ?: return
        if (loadingOlder) return
        viewModelScope.launch { loadingOlder = true; runCatching { chat.loadMessages(channelId, oldest) }; loadingOlder = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val readOnly = vm.channelName == "announcements" && !vm.canAnnounce
    LaunchedEffect(messages.firstOrNull()?.id) { if (messages.isNotEmpty()) list.animateScrollToItem(0) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Column { Text("# ${vm.channelName}"); Text("Live updates on", style = MaterialTheme.typography.labelSmall, color = Nx.Lime) } },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                if (readOnly) Text("Only admins can post announcements.", Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    color = LocalNx.current.mute, style = MaterialTheme.typography.bodySmall)
                else Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message #${vm.channelName}") },
                        shape = RoundedCornerShape(20.dp), colors = nxFieldColors(), maxLines = 5)
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton({ vm.send(text); text = "" }, enabled = text.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Nx.Violet)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        },
    ) { pad ->
        AuroraBackground(Modifier.padding(pad), intensity = .4f) {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.senderId == vm.me
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!mine) { Avatar(m.senderName, 28.dp); Spacer(Modifier.width(8.dp)) }
                        Column(
                            Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp))
                                .background(if (mine) Nx.Violet else MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            if (!mine) Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = Nx.Cyan)
                            Text(m.body, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground)
                            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                Text(fmt.format(m.at), style = MaterialTheme.typography.labelSmall,
                                    color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = .75f) else LocalNx.current.mute)
                                if (mine) { Spacer(Modifier.width(4.dp))
                                    Icon(if (m.pending) Icons.Rounded.Schedule else Icons.Rounded.DoneAll, if (m.pending) "Sending" else "Sent",
                                        Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = .75f)) }
                            }
                        }
                    }
                }
                item {
                    if (messages.size >= 40) TextButton({ vm.older() }, Modifier.fillMaxWidth()) { Text(if (vm.loadingOlder) "Loading…" else "Load earlier messages") }
                }
            }
        }
    }
}
