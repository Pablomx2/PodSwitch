package com.podswitch.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.podswitch.R
import com.podswitch.SwitchAcceptReceiver
import com.podswitch.core.NotificationPresenter

/** Android [NotificationPresenter]: notification channels, foreground notification, and ASK prompt. */
class AndroidNotificationPresenter(
    private val context: Context,
) : NotificationPresenter {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_service_desc) }

        val ask = NotificationChannel(
            CHANNEL_ASK,
            context.getString(R.string.channel_ask_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_ask_desc) }

        manager.createNotificationChannels(listOf(service, ask))
    }

    /** The ongoing notification shown by the foreground service. */
    fun buildOngoing(): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.ongoing_title))
            .setContentText(context.getString(R.string.ongoing_text))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()

    override fun showAsk() {
        val acceptIntent = Intent(context, SwitchAcceptReceiver::class.java).apply {
            action = SwitchAcceptReceiver.ACTION_ACCEPT
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_ACCEPT,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val action = Notification.Action.Builder(
            null,
            context.getString(R.string.ask_action_connect),
            pending,
        ).build()

        val notification = Notification.Builder(context, CHANNEL_ASK)
            .setContentTitle(context.getString(R.string.ask_title))
            .setContentText(context.getString(R.string.ask_text))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setAutoCancel(true)
            .addAction(action)
            .build()

        manager.notify(ID_ASK, notification)
    }

    override fun clearAsk() {
        manager.cancel(ID_ASK)
    }

    companion object {
        const val CHANNEL_SERVICE = "podswitch.service"
        const val CHANNEL_ASK = "podswitch.ask"

        const val ID_ONGOING = 1
        const val ID_ASK = 2

        private const val REQUEST_ACCEPT = 100
    }
}
