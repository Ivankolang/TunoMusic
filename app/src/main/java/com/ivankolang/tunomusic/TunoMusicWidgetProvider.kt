package com.ivankolang.tunomusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import java.net.URL

class TunoMusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TunoMusicWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicPlaybackService::class.java).setAction(action)
            return PendingIntent.getService(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("tuno_widget", Context.MODE_PRIVATE)
            val title = prefs.getString("title", "Tuno Music") ?: "Tuno Music"
            val artist = prefs.getString("artist", "Твоя музыка") ?: "Твоя музыка"
            val artwork = prefs.getString("artwork", "") ?: ""
            val playing = prefs.getBoolean("playing", false)
            val source = prefs.getString("source", "none") ?: "none"
            val views = RemoteViews(context.packageName, R.layout.tuno_music_widget)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setImageViewResource(
                R.id.widget_play,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                actionIntent(context, MusicPlaybackService.ACTION_WIDGET_PREVIOUS, 101)
            )
            views.setOnClickPendingIntent(
                R.id.widget_play,
                actionIntent(context, MusicPlaybackService.ACTION_WIDGET_TOGGLE, 102)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                actionIntent(context, MusicPlaybackService.ACTION_WIDGET_NEXT, 103)
            )
            val open = PendingIntent.getActivity(
                context, 104,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_cover, open)
            views.setOnClickPendingIntent(R.id.widget_text_area, open)
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_tuno_launcher)
            manager.updateAppWidget(id, views)

            if (artwork.startsWith("content://") || artwork.startsWith("file://")) {
                views.setImageViewUri(R.id.widget_cover, Uri.parse(artwork))
                manager.updateAppWidget(id, views)
            } else if (artwork.startsWith("https://") || artwork.startsWith("http://")) {
                Thread {
                    val bitmap = runCatching {
                        URL(artwork).openConnection().apply {
                            connectTimeout = 5000
                            readTimeout = 5000
                        }.getInputStream().use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_cover, bitmap)
                        manager.updateAppWidget(id, views)
                    }
                }.start()
            }

            // A station or track can still be opened even when playback is stopped.
            if (source == "none") {
                views.setTextViewText(R.id.widget_title, "Tuno Music")
                views.setTextViewText(R.id.widget_artist, "Нажми, чтобы открыть")
                manager.updateAppWidget(id, views)
            }
        }
    }
}
