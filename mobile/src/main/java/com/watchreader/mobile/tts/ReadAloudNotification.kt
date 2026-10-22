package com.watchreader.mobile.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.watchreader.mobile.R

/**
 * The notification that stands for the voice while it reads: a media notification tied to the
 * session, with previous sentence, play or pause, next sentence and stop. Before Android 13 its
 * buttons are its own actions, sent to the service as intents; from 13 on the system draws the
 * controls from the session instead, and the actions here are only a fallback.
 */
internal class ReadAloudNotification(private val context: Context, private val serviceIntent: (String) -> Intent) {
    /** Low importance: no sound and no heads-up for a notification that changes with every pause. */
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.listen_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun build(title: String, status: String, playing: Boolean, cover: Bitmap?, token: MediaSession.Token): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listen_notification)
            .setContentTitle(title)
            .setContentText(status)
            .apply { if (cover != null) setLargeIcon(cover) }
            .setContentIntent(openApp(context))
            .setDeleteIntent(service(ACTION_STOP_CODE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_listen_previous, R.string.listen_action_previous, ACTION_PREVIOUS_CODE))
            .addAction(
                if (playing) action(R.drawable.ic_listen_pause, R.string.listen_action_pause, ACTION_PAUSE_CODE)
                else action(R.drawable.ic_listen_play, R.string.listen_action_play, ACTION_RESUME_CODE),
            )
            .addAction(action(R.drawable.ic_listen_next, R.string.listen_action_next, ACTION_NEXT_CODE))
            .addAction(action(R.drawable.ic_listen_stop, R.string.listen_action_stop, ACTION_STOP_CODE))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
            .build()

    private fun action(@DrawableRes icon: Int, @StringRes label: Int, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), service(code)).build()

    private fun service(code: Int): PendingIntent = PendingIntent.getService(
        context, code, serviceIntent(ACTIONS.getValue(code)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "read_aloud"
        const val ID = 42

        // Request codes, one per action, so the PendingIntents are told apart.
        private const val ACTION_PREVIOUS_CODE = 1
        private const val ACTION_PAUSE_CODE = 2
        private const val ACTION_RESUME_CODE = 3
        private const val ACTION_NEXT_CODE = 4
        private const val ACTION_STOP_CODE = 5

        const val ACTION_PREVIOUS = "com.watchreader.listen.PREVIOUS"
        const val ACTION_PAUSE = "com.watchreader.listen.PAUSE"
        const val ACTION_RESUME = "com.watchreader.listen.RESUME"
        const val ACTION_NEXT = "com.watchreader.listen.NEXT"
        const val ACTION_STOP = "com.watchreader.listen.STOP"

        private val ACTIONS = mapOf(
            ACTION_PREVIOUS_CODE to ACTION_PREVIOUS,
            ACTION_PAUSE_CODE to ACTION_PAUSE,
            ACTION_RESUME_CODE to ACTION_RESUME,
            ACTION_NEXT_CODE to ACTION_NEXT,
            ACTION_STOP_CODE to ACTION_STOP,
        )

        /**
         * Brings the app back as it was left, the reader on the page the voice has reached, as a
         * tap on the launcher icon would; with the app closed it opens on the library, where the
         * book now shows the place listening reached.
         */
        fun openApp(context: Context): PendingIntent {
            val launch = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
