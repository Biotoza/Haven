package sh.haven.feature.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle

private const val TAG = "TerminalNotifications"
private const val CHANNEL_ID = "haven_terminal_notifications"
private const val CHANNEL_NAME = "Terminal Notifications"

/**
 * Show a terminal notification. Foreground: Toast. Background: Android notification.
 */
fun showTerminalNotification(context: Context, title: String, body: String, tabLabel: String) {
    Log.d(TAG, "showTerminalNotification: title='$title' body='$body' tab='$tabLabel'")

    val toastText = if (title.isNotEmpty()) "$title: $body" else body

    // Always show a toast (works regardless of foreground state)
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
    }

    // Also post a system notification when in background
    if (!isAppForeground()) return
    postSystemNotification(context, title.ifEmpty { tabLabel }, body)
}

/**
 * #753: whether a BEL should raise a system notification. Pure so the policy
 * is unit-testable without a Context: only when the setting is on and Haven
 * is not in the foreground. Foreground BEL stays silent either way — shells
 * ring BEL for ordinary things (backspace at an empty prompt, ambiguous
 * completion) and a toast per bell would be noise.
 */
fun shouldNotifyOnBell(settingEnabled: Boolean, isForeground: Boolean): Boolean =
    settingEnabled && !isForeground

/**
 * #753: post the background BEL notification, naming the tab that rang. The
 * caller gates on the [shouldNotifyOnBell] setting; this checks the
 * foreground state itself so a bell that lands while the user is looking at
 * the terminal never posts.
 */
fun showBellNotification(context: Context, tabLabel: String) {
    if (isAppForeground()) return
    Log.d(TAG, "showBellNotification: tab='$tabLabel'")
    postSystemNotification(
        context,
        tabLabel.ifEmpty { context.getString(R.string.terminal_bell_notification_title) },
        context.getString(R.string.terminal_bell_notification_body),
    )
}

private fun isAppForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

private fun postSystemNotification(context: Context, title: String, body: String) {
    try {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    } catch (e: Exception) {
        Log.w(TAG, "Background notification failed", e)
    }
}
