package dev.kurobanekotlin.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** True only while [MainActivity] is resumed. Approvals notify when this is false. */
object AppVisibility {
    val resumed = MutableStateFlow(false)
}

/**
 * A normal notification posted from the activity. This is not a foreground service and not push.
 * Delivery while the process is dead is not claimed.
 */
object ApprovalNotifier {
    private const val CHANNEL = "approvals"

    const val UNRELIABLE =
        "This is not reliable delivery while the app is killed."

    fun notify(context: Context, tool: String, requestId: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown when a tool approval arrives while Kurobane is in the background. $UNRELIABLE"
            }
            mgr.createNotificationChannel(channel)
        }
        val text = "A tool needs a decision ($tool). $UNRELIABLE"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_approval)
            .setContentTitle("Approval needed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(requestId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was denied. The in-app card is still the decision UI.
        }
    }
}
