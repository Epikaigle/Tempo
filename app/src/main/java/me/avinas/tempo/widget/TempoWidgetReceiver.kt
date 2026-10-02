package me.avinas.tempo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.EntryPointAccessors
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.di.AnalyticsEntryPoint
import me.avinas.tempo.widget.utils.cancelPeriodicWidgetUpdate
import me.avinas.tempo.widget.utils.scheduleImmediateWidgetUpdate
import me.avinas.tempo.widget.utils.schedulePeriodicWidgetUpdate

class TempoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TempoAppWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        scheduleImmediateWidgetUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicWidgetUpdate(context)
        reportWidgetPlaced(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicWidgetUpdate(context)
    }

    /**
     * `onEnabled` fires the first time a widget is placed on a home screen — the only reliable
     * signal that a user actually adopted the widget, as opposed to merely opening the picker.
     * A broadcast receiver has no injection point, so the tracker is resolved through an entry
     * point, and a failure to do so must never break widget registration.
     */
    private fun reportWidgetPlaced(context: Context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, AnalyticsEntryPoint::class.java)
                .analyticsTracker()
                .track(FeatureUsed(TempoFeature.WIDGET))
        }
    }
}
