package com.devanshu.devnex

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devanshu.devnex.core.designsystem.DevNexTheme
import com.devanshu.devnex.feature.settings.AppPrefs
import com.devanshu.devnex.navigation.DevNexNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPrefs

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash shows the DevNex mark for a frame, then hands over to the animated Compose splash.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val invite = intent?.data?.takeIf { it.scheme == "devnex" && it.host == "join" }?.getQueryParameter("token")
        val taskId = intent?.getStringExtra("taskId")

        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val theme by prefs.theme.collectAsStateWithLifecycle()
            DevNexTheme(theme) {
                DevNexNavHost(pendingInvite = invite, pendingTaskId = taskId)
            }
        }
    }
}
