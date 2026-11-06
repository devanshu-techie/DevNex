package com.devanshu.devnex.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devanshu.devnex.MainActivity
import com.devanshu.devnex.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

object NotificationChannels {
    const val TASKS = "devnex_tasks"
    const val MESSAGES = "devnex_messages"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, id: Int, title: String, body: String, taskId: String?, channel: String = TASKS) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            taskId?.let { putExtra("taskId", it) }
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_devnex_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }   // no-op if permission denied
    }
}

/**
 * Receives pushes sent by the server-side Edge Function (supabase/functions/push).
 * The app never holds the FCM server key. Activate by adding google-services.json (see README).
 */
class DevNexMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "DevNex"
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        NotificationChannels.show(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), title, body, message.data["task_id"])
    }

    override fun onNewToken(token: String) {
        // Stored by the app after sign-in into device_tokens (RLS: own rows only).
        getSharedPreferences("devnex_prefs", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
}
