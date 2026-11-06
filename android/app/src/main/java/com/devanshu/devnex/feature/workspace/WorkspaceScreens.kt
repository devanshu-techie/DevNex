package com.devanshu.devnex.feature.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.model.Industry
import com.devanshu.devnex.domain.model.Workspace
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repo: WorkspaceRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val workspaces = repo.workspaces
    val active = repo.active
    val user = auth.session
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    init { viewModelScope.launch { runCatching { repo.refresh() } } }

    fun select(w: Workspace, done: () -> Unit) = act {
        if (!w.isActive) throw IllegalStateException("${w.name} is ${w.status}. Contact the platform owner.")
        repo.select(w.companyId); done()
    }
    fun create(name: String, slug: String, industry: Industry, done: () -> Unit) = act {
        if (name.trim().length < 2) throw IllegalArgumentException("Company name is too short")
        repo.createCompany(name, slug, industry); done()
    }
    fun join(token: String, done: () -> Unit) = act { repo.joinWithInvite(token); done() }
    fun signOut(done: () -> Unit) = act { auth.signOut(); repo.clear(); done() }

    private fun act(block: suspend () -> Unit) {
        if (loading) return
        viewModelScope.launch {
            loading = true; error = null
            try { block() } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }
}

@Composable
fun WorkspaceSwitcherScreen(
    onOpen: () -> Unit, onCreate: () -> Unit, onJoin: () -> Unit, onSignedOut: () -> Unit, onBack: (() -> Unit)? = null,
    vm: WorkspaceViewModel = hiltViewModel(),
) {
    val list by vm.workspaces.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    AuroraBackground {
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ vm.signOut(onSignedOut) }) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Hi ${user?.fullName?.substringBefore(' ') ?: ""} 👋", style = MaterialTheme.typography.headlineMedium)
                Text(if (list.isEmpty()) "You're not in any company yet." else "Choose a workspace", color = LocalNx.current.mute)
                Spacer(Modifier.height(12.dp))
                ErrorBanner(vm.error) { vm.error = null }
            }
            items(list, key = { it.companyId }) { w ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { vm.select(w, onOpen) }, accent = Color(w.brandColor)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(w.brandColor)), contentAlignment = Alignment.Center) {
                            Text(w.name.take(1), color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            Text("${w.roleName} · ${w.industry.label}", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                        }
                        when {
                            !w.isActive -> Pill(w.status.replaceFirstChar { it.uppercase() }, Nx.Red)
                            active?.companyId == w.companyId -> Pill("Current", Nx.Lime)
                            else -> Icon(Icons.Rounded.ChevronRight, null, tint = LocalNx.current.mute)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassCard(Modifier.weight(1f), onClick = onCreate) {
                        Icon(Icons.Rounded.Domain, null, tint = Nx.Violet)
                        Spacer(Modifier.height(8.dp))
                        Text("Create a company", style = MaterialTheme.typography.titleSmall)
                        Text("You become its owner", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                    GlassCard(Modifier.weight(1f), onClick = onJoin) {
                        Icon(Icons.Rounded.QrCode, null, tint = Nx.Cyan)
                        Spacer(Modifier.height(8.dp))
                        Text("Join with code", style = MaterialTheme.typography.titleSmall)
                        Text("From your invitation", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    }
                }
            }
        }
    }
}

@Composable
fun CreateCompanyScreen(onBack: () -> Unit, onCreated: () -> Unit, vm: WorkspaceViewModel = hiltViewModel()) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var slugEdited by remember { mutableStateOf(false) }
    var industry by remember { mutableStateOf(Industry.GENERAL) }

    AuroraBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            IconButton({ if (step == 0) onBack() else step-- }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            LinearProgressIndicator(progress = { (step + 1) / 2f }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = Nx.Cyan, trackColor = LocalNx.current.line)
            Spacer(Modifier.height(24.dp))
            if (step == 0) {
                Text("Name your company", style = MaterialTheme.typography.headlineMedium)
                Text("This becomes your private workspace.", color = LocalNx.current.mute)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(name, { name = it; if (!slugEdited) slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) },
                    Modifier.fillMaxWidth(), label = { Text("Company name") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), colors = nxFieldColors())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(slug, { slug = it.lowercase(); slugEdited = true }, Modifier.fillMaxWidth(), label = { Text("Workspace URL") },
                    prefix = { Text("devnex.app/", color = LocalNx.current.mute) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
                Spacer(Modifier.height(24.dp))
                GradientButton("Continue", Modifier.fillMaxWidth(), enabled = name.trim().length >= 2 && slug.length >= 3) { step = 1 }
            } else {
                Text("What kind of work?", style = MaterialTheme.typography.headlineMedium)
                Text("We'll set up departments, roles and modules. You can change everything later.", color = LocalNx.current.mute)
                Spacer(Modifier.height(20.dp))
                Industry.entries.forEach { ind ->
                    val sel = ind == industry
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { industry = ind }, accent = if (sel) Nx.Violet else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (ind) {
                                Industry.GENERAL -> Icons.Rounded.Business; Industry.NEWSROOM -> Icons.Rounded.Description
                                Industry.AGENCY -> Icons.Rounded.Announcement; Industry.SOFTWARE -> Icons.Rounded.Code
                            }, null, tint = if (sel) Nx.Cyan else LocalNx.current.mute)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ind.label, style = MaterialTheme.typography.titleSmall)
                                Text(ind.blurb, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                            }
                            RadioButton(sel, { industry = ind })
                        }
                    }
                }
                AnimatedVisibility(vm.error != null) { Text(vm.error.orEmpty(), color = Nx.Red) }
                Spacer(Modifier.height(16.dp))
                GradientButton("Launch workspace", Modifier.fillMaxWidth(), loading = vm.loading, icon = Icons.Rounded.FlashOn) {
                    vm.create(name, slug, industry, onCreated)
                }
            }
        }
    }
}

@Composable
fun JoinCompanyScreen(onBack: () -> Unit, onJoined: () -> Unit, initialToken: String = "", vm: WorkspaceViewModel = hiltViewModel()) {
    var token by remember { mutableStateOf(initialToken) }
    AuroraBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.height(16.dp))
            Text("Join a company", style = MaterialTheme.typography.headlineMedium)
            Text("Paste the invitation code your admin sent. It only works for the email it was sent to, and expires in 7 days.",
                color = LocalNx.current.mute)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Invitation code") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.VpnKey, null) }, shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            AnimatedVisibility(vm.error != null) { Text(vm.error.orEmpty(), color = Nx.Red, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            GradientButton("Join workspace", Modifier.fillMaxWidth(), enabled = token.length >= 6, loading = vm.loading) { vm.join(token, onJoined) }
        }
    }
}
