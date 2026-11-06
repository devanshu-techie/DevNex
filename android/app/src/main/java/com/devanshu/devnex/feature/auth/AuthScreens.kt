package com.devanshu.devnex.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.core.designsystem.component.*
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AfterAuth { MFA, WORKSPACES, MAIN }

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val workspaces: WorkspaceRepository,
) : ViewModel() {
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    val isDemo get() = auth.isDemo

    fun signIn(email: String, password: String, done: (AfterAuth) -> Unit) = act {
        if (email.isBlank() || password.isBlank()) { error = "Enter your email and password"; return@act }
        auth.signIn(email, password).let { u -> if (u.needsMfa) AfterAuth.MFA else next() }.let(done)
    }

    fun signUp(name: String, email: String, password: String, done: (AfterAuth) -> Unit) = act {
        if (name.isBlank()) { error = "Tell us your name"; return@act }
        if (!email.contains('@')) { error = "Enter a valid email"; return@act }
        auth.signUp(email, password, name); done(next())
    }

    fun verify(code: String, done: (AfterAuth) -> Unit) = act {
        auth.verifyMfa(code); done(next())
    }

    private suspend fun next(): AfterAuth {
        workspaces.refresh()
        return if (workspaces.active.value != null) AfterAuth.MAIN else AfterAuth.WORKSPACES
    }

    private fun act(block: suspend () -> Unit) {
        if (loading) return
        viewModelScope.launch {
            loading = true; error = null
            try { block() } catch (e: Exception) { error = e.message ?: "Something went wrong" } finally { loading = false }
        }
    }
}

@Composable
fun LoginScreen(onAuthed: (AfterAuth) -> Unit, onRegister: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    AuroraBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding().padding(24.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            BrandMark()
            Spacer(Modifier.height(36.dp))
            Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
            Text("Sign in to your workspace", style = MaterialTheme.typography.bodyMedium, color = LocalNx.current.mute)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Work email") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) }, singleLine = true,
                trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password") } },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(16.dp), colors = nxFieldColors())

            AnimatedVisibility(vm.error != null) {
                Text(vm.error.orEmpty(), color = Nx.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(20.dp))
            GradientButton("Sign in", Modifier.fillMaxWidth(), loading = vm.loading, icon = Icons.Rounded.FlashOn) {
                vm.signIn(email, password, onAuthed)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onRegister, Modifier.align(Alignment.CenterHorizontally)) { Text("New here? Create an account") }

            if (vm.isDemo) {
                Spacer(Modifier.height(16.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = Nx.Cyan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = Nx.Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Demo mode", style = MaterialTheme.typography.labelSmall, color = Nx.Cyan)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tap a role to try it. Password: devnex123", style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "owner@devnex.app" to "Devanshu · Platform owner + Company owner",
                        "head@devnex.app" to "Priya · Editorial head (approver)",
                        "lead@devnex.app" to "Arjun · Reporting team lead",
                        "reporter@devnex.app" to "Kabir · Reporter",
                        "editor@devnex.app" to "Neha · Video editor",
                    ).forEach { (e, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { email = e; password = "devnex123"; vm.signIn(e, "devnex123", onAuthed) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(label.substringBefore(' '), 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(e, style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = LocalNx.current.mute)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Powered by Devanshu", style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Nx.Brand), contentAlignment = Alignment.Center) {
            Text("N", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text("DevNex", style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 24.sp))
    }
}

@Composable
fun RegisterScreen(onBack: () -> Unit, onAuthed: (AfterAuth) -> Unit, vm: AuthViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuroraBackground {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding().padding(24.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.height(12.dp))
            Text("Create your account", style = MaterialTheme.typography.headlineMedium)
            Text("One account, any number of companies.", color = LocalNx.current.mute)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Person, null) }, shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Work email") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password (8+ characters)") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Lock, null) }, visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp), colors = nxFieldColors())
            PasswordStrength(password)
            AnimatedVisibility(vm.error != null) { Text(vm.error.orEmpty(), color = Nx.Red, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            GradientButton("Create account", Modifier.fillMaxWidth(), loading = vm.loading) { vm.signUp(name, email, password, onAuthed) }
        }
    }
}

@Composable
private fun PasswordStrength(pw: String) {
    val score = listOf(pw.length >= 8, pw.any { it.isDigit() }, pw.any { it.isUpperCase() }, pw.any { !it.isLetterOrDigit() }).count { it }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i < score) listOf(Nx.Red, Nx.Amber, Nx.Cyan, Nx.Lime)[score - 1] else LocalNx.current.line))
        }
    }
}

@Composable
fun MfaScreen(onVerified: (AfterAuth) -> Unit, onBack: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AuroraBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Nx.Violet), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Two-step verification", style = MaterialTheme.typography.headlineSmall)
            Text("Owners and admins confirm with an authenticator app.", color = LocalNx.current.mute, textAlign = TextAlign.Center)
            if (vm.isDemo) Text("Demo code: 123456", color = Nx.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(28.dp))
            BasicTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6); if (code.length == 6) vm.verify(code, onVerified) },
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(6) { i ->
                            val filled = i < code.length; val active = i == code.length
                            Box(
                                Modifier.size(46.dp, 58.dp).clip(RoundedCornerShape(14.dp)).background(LocalNx.current.glass)
                                    .border(if (active) 2.dp else 1.dp, if (active) Nx.Cyan else if (filled) Nx.Violet else LocalNx.current.line, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text(code.getOrNull(i)?.toString() ?: "", fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                },
            )
            AnimatedVisibility(vm.error != null) { Text(vm.error.orEmpty(), color = Nx.Red, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
            GradientButton("Verify", Modifier.fillMaxWidth(), enabled = code.length == 6, loading = vm.loading) { vm.verify(code, onVerified) }
        }
    }
}
