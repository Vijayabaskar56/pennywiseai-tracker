package com.pennywiseai.tracker.worker

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.pennywiseai.tracker.MainActivity
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.core.ResText
import com.pennywiseai.tracker.data.sharedexpenses.SharedActivityLines
import com.pennywiseai.tracker.data.sharedexpenses.SharedLocalStore
import com.pennywiseai.tracker.data.sharedexpenses.TypedText
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.utils.bidiIsolate
import com.pennywiseai.tracker.widget.inAppLanguage

/**
 * One notification per Shared group for what other members changed since the
 * last sync (docs/shared-expenses.md, "Activity feed"). A newer one replaces the
 * group's older one. Nothing shows while the app is open: the feed is on screen.
 * Figures stay off the lock screen, like the other alerts (#734).
 */
object SharedActivityNotifications {
    private const val CHANNEL_ID = "shared_expenses"
    private const val TAG = "shared_activity"
    private const val MAX_LINES = 5

    suspend fun show(context: Context, store: SharedLocalStore, activity: List<SharedActivity>) {
        if (activity.isEmpty() || appInForeground()) return
        val res = context.inAppLanguage()
        for ((groupId, rows) in activity.groupBy { it.groupId }) {
            val group = store.group(groupId)?.takeIf { it.deletedAt == null } ?: continue
            val names = store.members(groupId).associate { it.id to it.displayName }
            val lines = rows.sortedByDescending { it.seq }.take(MAX_LINES)
                .map { SharedActivityLines.line(it, group.myMemberId, names, maskIfHidden = false).resolve(res) }
            post(context, groupId, res.bidiIsolate(group.name), lines, rows.size)
        }
    }

    private fun post(context: Context, groupId: String, title: String, lines: List<String>, count: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val res = context.inAppLanguage()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                res.getString(R.string.shared_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = res.getString(R.string.shared_notification_channel_description) },
        )
        val text = if (count == 1) lines.first() else res.resources.getQuantityString(R.plurals.shared_notification_changes, count, count)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // The data URI keeps each group's PendingIntent apart.
            data = "pennywise-alert://shared/$groupId".toUri()
            putExtra(MainActivity.EXTRA_OPEN_SHARED_GROUP, groupId)
        }
        manager.notify(
            TAG,
            groupId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        groupId.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    private fun appInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** A feed line as text; what a person typed is isolated so it cannot reorder the sentence. */
    private fun ResText.resolve(context: Context): String {
        val resolved = args.map {
            when (it) {
                is ResText -> it.resolve(context)
                is TypedText -> context.bidiIsolate(it.value)
                else -> it
            }
        }
        return context.getString(id, *resolved.toTypedArray())
    }
}
