package com.podswitch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.podswitch.platform.AndroidSettingsStore

/** Home-screen widget: tap anywhere to toggle PodSwitch on/off. */
class PodSwitchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val enabled = AndroidSettingsStore(context.applicationContext).currentConfig().enabled
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(context, enabled)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            PodSwitchControl.toggle(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.podswitch.action.WIDGET_TOGGLE"

        /** Re-renders every placed instance of this widget; called after enabled state changes. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PodSwitchWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val enabled = AndroidSettingsStore(context.applicationContext).currentConfig().enabled
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, enabled)) }
        }

        private fun buildViews(context: Context, enabled: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_podswitch)
            views.setImageViewResource(
                R.id.widget_image,
                if (enabled) R.drawable.widget_on else R.drawable.widget_off,
            )

            val toggleIntent = Intent(context, PodSwitchWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePending = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_image, togglePending)

            return views
        }
    }
}
