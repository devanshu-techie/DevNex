package com.devanshu.devnex.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devanshu.devnex.feature.admin.AdminScreen
import com.devanshu.devnex.feature.admin.PlatformScreen
import com.devanshu.devnex.feature.auth.*
import com.devanshu.devnex.feature.chat.ConversationScreen
import com.devanshu.devnex.feature.coverage.EventScreen
import com.devanshu.devnex.feature.dashboard.MainNav
import com.devanshu.devnex.feature.dashboard.MainScreen
import com.devanshu.devnex.feature.settings.NotificationsScreen
import com.devanshu.devnex.feature.settings.SettingsScreen
import com.devanshu.devnex.feature.splash.SplashScreen
import com.devanshu.devnex.feature.splash.StartDestination
import com.devanshu.devnex.feature.tasks.CreateTaskScreen
import com.devanshu.devnex.feature.tasks.TaskDetailScreen
import com.devanshu.devnex.feature.team.InsightsScreen
import com.devanshu.devnex.feature.team.TeamScreen
import com.devanshu.devnex.feature.workspace.CreateCompanyScreen
import com.devanshu.devnex.feature.workspace.JoinCompanyScreen
import com.devanshu.devnex.feature.workspace.WorkspaceSwitcherScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val MFA = "mfa"
    const val WORKSPACES = "workspaces"
    const val CREATE_COMPANY = "create_company"
    const val JOIN = "join?token={token}"
    const val MAIN = "main"
    const val TASK = "task/{taskId}"
    const val CREATE_TASK = "create_task?parentId={parentId}"
    const val CHANNEL = "channel/{channelId}/{name}"
    const val EVENT = "event/{eventId}/{name}"
    const val NOTIFICATIONS = "notifications"
    const val TEAM = "team"
    const val INSIGHTS = "insights"
    const val ADMIN = "admin"
    const val PLATFORM = "platform"
    const val SETTINGS = "settings"

    fun task(id: String) = "task/$id"
    fun createTask(parentId: String?) = "create_task?parentId=${parentId ?: "none"}"
    fun channel(id: String, name: String) = "channel/$id/${Uri.encode(name)}"
    fun event(id: String, name: String) = "event/$id/${Uri.encode(name)}"
    fun join(token: String = "") = "join?token=${Uri.encode(token)}"
}

@Composable
fun DevNexNavHost(pendingInvite: String?, pendingTaskId: String?, nav: NavHostController = rememberNavController()) {
    fun resetTo(route: String) = nav.navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
    fun after(a: AfterAuth) = when (a) {
        AfterAuth.MFA -> nav.navigate(Routes.MFA)
        AfterAuth.WORKSPACES -> resetTo(Routes.WORKSPACES)
        AfterAuth.MAIN -> resetTo(Routes.MAIN)
    }

    NavHost(
        nav, startDestination = Routes.SPLASH,
        enterTransition = { slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(250)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(280)) },
    ) {
        composable(Routes.SPLASH, enterTransition = { fadeIn() }, exitTransition = { fadeOut(tween(500)) }) {
            SplashScreen(onDone = { d ->
                when (d) {
                    StartDestination.LOGIN -> resetTo(Routes.LOGIN)
                    StartDestination.MFA -> resetTo(Routes.MFA)
                    StartDestination.WORKSPACES -> resetTo(if (pendingInvite != null) Routes.join(pendingInvite) else Routes.WORKSPACES)
                    StartDestination.MAIN -> {
                        resetTo(Routes.MAIN)
                        if (pendingInvite != null) nav.navigate(Routes.join(pendingInvite))
                        else if (pendingTaskId != null) nav.navigate(Routes.task(pendingTaskId))
                    }
                }
            })
        }
        composable(Routes.LOGIN) { LoginScreen(onAuthed = ::after, onRegister = { nav.navigate(Routes.REGISTER) }) }
        composable(Routes.REGISTER) { RegisterScreen(onBack = { nav.popBackStack() }, onAuthed = ::after) }
        composable(Routes.MFA) { MfaScreen(onVerified = ::after, onBack = { resetTo(Routes.LOGIN) }) }

        composable(Routes.WORKSPACES) {
            WorkspaceSwitcherScreen(
                onOpen = { resetTo(Routes.MAIN) },
                onCreate = { nav.navigate(Routes.CREATE_COMPANY) },
                onJoin = { nav.navigate(Routes.join()) },
                onSignedOut = { resetTo(Routes.LOGIN) },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack(); Unit }) else null,
            )
        }
        composable(Routes.CREATE_COMPANY) { CreateCompanyScreen(onBack = { nav.popBackStack() }, onCreated = { resetTo(Routes.MAIN) }) }
        composable(Routes.JOIN, arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" })) { e ->
            JoinCompanyScreen(onBack = { nav.popBackStack() }, onJoined = { resetTo(Routes.MAIN) },
                initialToken = e.arguments?.getString("token").orEmpty())
        }

        composable(Routes.MAIN) {
            MainScreen(MainNav(
                openTask = { nav.navigate(Routes.task(it)) },
                createTask = { nav.navigate(Routes.createTask(it)) },
                openChannel = { id, name -> nav.navigate(Routes.channel(id, name)) },
                openEvent = { id, name -> nav.navigate(Routes.event(id, name)) },
                switchWorkspace = { nav.navigate(Routes.WORKSPACES) },
                notifications = { nav.navigate(Routes.NOTIFICATIONS) },
                team = { nav.navigate(Routes.TEAM) },
                insights = { nav.navigate(Routes.INSIGHTS) },
                admin = { nav.navigate(Routes.ADMIN) },
                platform = { nav.navigate(Routes.PLATFORM) },
                settings = { nav.navigate(Routes.SETTINGS) },
                signedOut = { resetTo(Routes.LOGIN) },
            ))
        }
        composable(Routes.TASK, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) {
            TaskDetailScreen(onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate(Routes.task(it)) },
                onAddSubtask = { nav.navigate(Routes.createTask(it)) })
        }
        composable(Routes.CREATE_TASK, arguments = listOf(navArgument("parentId") { type = NavType.StringType; defaultValue = "none" })) {
            CreateTaskScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                nav.popBackStack(); nav.navigate(Routes.task(id))
            })
        }
        composable(Routes.CHANNEL, arguments = listOf(navArgument("channelId") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) {
            ConversationScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EVENT, arguments = listOf(navArgument("eventId") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) {
            EventScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen(onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate(Routes.task(it)) }) }
        composable(Routes.TEAM) { TeamScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.INSIGHTS) { InsightsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ADMIN) { AdminScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PLATFORM) { PlatformScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
