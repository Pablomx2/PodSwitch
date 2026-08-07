package com.podswitch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.podswitch.platform.AndroidSettingsStore
import com.podswitch.ui.ConfigActivity

/**
 * 1x1 home-screen widget with two tap zones: the icon toggles PodSwitch on/off, the state label
 * opens the app (there's no OS hook to redirect a Quick Settings tile's long-press to the app, so
 * this widget is the one-tap path there).
 */
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
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (enabled) R.drawable.widget_bg_on else R.drawable.widget_bg_off,
            )
            views.setTextViewText(
                R.id.widget_state,
                context.getString(if (enabled) R.string.widget_state_on else R.string.widget_state_off),
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
            views.setOnClickPendingIntent(R.id.widget_icon, togglePending)

            val openIntent = Intent(context, ConfigActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_state, openPending)

            return views
        }
    }
}
