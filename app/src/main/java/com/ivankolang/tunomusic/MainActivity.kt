package com.ivankolang.tunomusic

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import android.app.DownloadManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

private val Night = Color(0xFF080B0A)
private val Panel = Color(0xFF151B18)
private val Green = Color(0xFF9BE7B1)
private val Muted = Color(0xFF9AA69E)

private fun readPlaylists(context: Context): List<Playlist> {
    val raw = context.getSharedPreferences("tuno_playlists", Context.MODE_PRIVATE)
        .getString("items", "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val ids = item.optJSONArray("trackIds") ?: JSONArray()
                val trackIds = buildList {
                    for (j in 0 until ids.length()) add(ids.optLong(j))
                }
                val cloudArray = item.optJSONArray("soundCloudTracks") ?: JSONArray()
                val cloudTracks = buildList {
                    for (j in 0 until cloudArray.length()) {
                        val t = cloudArray.optJSONObject(j) ?: continue
                        val url = t.optString("url")
                        if (url.isNotBlank()) add(SoundCloudResult(t.optString("title"), t.optString("artist"), url,
                            t.optString("artwork").takeIf { it.startsWith("http") }, t.optBoolean("isPlaylist")))
                    }
                }
                add(Playlist(item.optString("id"), item.optString("name"), trackIds, cloudTracks))
            }
        }
    }.getOrDefault(emptyList())
}

private fun savePlaylists(context: Context, playlists: List<Playlist>) {
    val array = JSONArray()
    playlists.forEach { playlist ->
        val ids = JSONArray()
        playlist.trackIds.forEach { trackId -> ids.put(trackId) }
        val cloud = JSONArray()
        playlist.soundCloudTracks.forEach { t ->
            cloud.put(JSONObject().put("title", t.title).put("artist", t.artist).put("url", t.url).put("artwork", t.artwork ?: "").put("isPlaylist", t.isPlaylist))
        }
        array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("trackIds", ids).put("soundCloudTracks", cloud))
    }
    context.getSharedPreferences("tuno_playlists", Context.MODE_PRIVATE).edit()
        .putString("items", array.toString()).apply()
}


private fun importOfflineAudio(context: Context, uri: Uri): Boolean {
    return runCatching {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "audio_" + System.currentTimeMillis()
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9А-Яа-я._ -]"), "_")
        val extByName = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 6 }
        val extension = extByName ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "audio"
        val baseName = safeName.substringBeforeLast('.', safeName).ifBlank { "Трек" }
        val folder = File(context.filesDir, "offline_music").apply { mkdirs() }
        val target = File(folder, baseName.take(60) + "_" + UUID.randomUUID() + "." + extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: return false
        target.length() > 0L
    }.getOrDefault(false)
}

data class Playlist(val id: String, val name: String, val trackIds: List<Long>, val soundCloudTracks: List<SoundCloudResult> = emptyList())
data class SoundCloudResult(val title: String, val artist: String, val url: String, val artwork: String?, val isPlaylist: Boolean = false)
data class AudiusTrack(val id: String, val title: String, val artist: String, val artwork: String?, val streamUrl: String, val downloadUrl: String, val isDownloadable: Boolean)
private fun readCloudFavorites(context: Context): List<SoundCloudResult> {
    val raw = context.getSharedPreferences("tuno_soundcloud", Context.MODE_PRIVATE).getString("favorites", "[]") ?: "[]"
    return runCatching {
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val url = t.optString("url")
                if (url.isNotBlank()) add(SoundCloudResult(t.optString("title"), t.optString("artist"), url,
                    t.optString("artwork").takeIf { it.startsWith("http") }))
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveCloudFavorites(context: Context, tracks: List<SoundCloudResult>) {
    val a = JSONArray()
    tracks.forEach { t -> a.put(JSONObject().put("title", t.title).put("artist", t.artist).put("url", t.url).put("artwork", t.artwork ?: "")) }
    context.getSharedPreferences("tuno_soundcloud", Context.MODE_PRIVATE).edit().putString("favorites", a.toString()).apply()
}

private fun readSearchHistory(context: Context): List<String> {
    val raw = context.getSharedPreferences("tuno_search", Context.MODE_PRIVATE)
        .getString("history", "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }.distinctBy { it.lowercase() }.take(10)
    }.getOrDefault(emptyList())
}

private fun saveSearchHistory(context: Context, history: List<String>) {
    val array = JSONArray()
    history.take(10).forEach { array.put(it) }
    context.getSharedPreferences("tuno_search", Context.MODE_PRIVATE).edit()
        .putString("history", array.toString()).apply()
}

private fun updateFavoriteOrder(context: Context, trackId: Long, isFavorite: Boolean) {
    val prefs = context.getSharedPreferences("track_edits", Context.MODE_PRIVATE)
    val order = runCatching {
        val json = JSONArray(prefs.getString("favorite_order", "[]") ?: "[]")
        buildList { for (i in 0 until json.length()) add(json.optLong(i)) }
    }.getOrDefault(emptyList())
    val updated = if (isFavorite) (order.filterNot { it == trackId } + trackId) else order.filterNot { it == trackId }
    val json = JSONArray()
    updated.forEach { json.put(it) }
    prefs.edit().putString("favorite_order", json.toString()).apply()
}

private class SoundCloudBridge(private val onResults: (String) -> Unit) {
    @JavascriptInterface
    fun onResults(json: String) = onResults.invoke(json)
}

private class SoundCloudWidgetBridge(
    private val onPlayback: (Boolean) -> Unit,
    private val onProgress: (Long, Long) -> Unit,
    private val onFinished: () -> Unit
) {
    @JavascriptInterface
    fun onPlayback(playing: Boolean) = onPlayback.invoke(playing)
    @JavascriptInterface
    fun onProgress(position: Long, duration: Long) = onProgress.invoke(position, duration)
    @JavascriptInterface
    fun onFinished() = onFinished.invoke()
}

private fun soundCloudWidgetHtml(widgetUrl: String): String {
    val safeUrl = widgetUrl.replace("&", "&amp;").replace("\"", "&quot;")
    return """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html,body{margin:0;padding:0;width:100%;height:100%;background:#080b0a;overflow:hidden}
          iframe{display:block;width:100%;height:100%;border:0}
        </style></head>
        <body>
          <iframe id="sc-widget" allow="autoplay" scrolling="no" src="$safeUrl"></iframe>
          <script src="https://w.soundcloud.com/player/api.js"></script>
          <script>
            (function(){
              var frame=document.getElementById('sc-widget');
              function attach(){
                if(!window.SC||!SC.Widget||!frame)return;
                var widget=SC.Widget(frame);
                widget.bind(SC.Widget.Events.READY,function(){
                  widget.getDuration(function(duration){
                    if(window.TunoWidget)window.TunoWidget.onProgress(0,duration||0);
                  });
                });
                widget.bind(SC.Widget.Events.PLAY,function(){
                  if(window.TunoWidget)window.TunoWidget.onPlayback(true);
                });
                widget.bind(SC.Widget.Events.FINISH,function(){
                  if(window.TunoWidget)window.TunoWidget.onFinished();
                });
                widget.bind(SC.Widget.Events.PAUSE,function(){
                  if(window.TunoWidget)window.TunoWidget.onPlayback(false);
                });
                widget.bind(SC.Widget.Events.PLAY_PROGRESS,function(data){
                  widget.getDuration(function(duration){
                    if(window.TunoWidget)window.TunoWidget.onProgress(data.currentPosition||0,duration||0);
                  });
                });
                window.tunoSeekTo=function(position){widget.seekTo(position);};
                window.tunoLoad=function(trackUrl){
                  if(!trackUrl)return false;
                  widget.load(trackUrl,{
                    auto_play:true,
                    show_comments:false,
                    visual:true,
                    color:"9BE7B1",
                    callback:function(){widget.play();}
                  });
                  return true;
                };
                window.tunoNext=function(){widget.next();};
                window.tunoPrevious=function(){widget.prev();};
                window.tunoToggle=function(){widget.toggle();};
                window.tunoPlay=function(){widget.play();};
                window.tunoPause=function(){widget.pause();};
              }
              if(window.SC)attach();else{
                var attempts=0,wait=setInterval(function(){
                  attempts++;if(window.SC){clearInterval(wait);attach();}
                  if(attempts>40)clearInterval(wait);
                },150);
              }
            })();
          </script>
        </body></html>
    """.trimIndent()
}


data class AudioTrack(
    val id: Long,
    val originalTitle: String,
    val originalArtist: String,
    val uri: Uri,
    val customTitle: String = originalTitle,
    val cover: String? = null,
    val isFavorite: Boolean = false
)

data class PlaybackQueueItem(val local: AudioTrack? = null, val cloud: SoundCloudResult? = null)

data class RadioStation(
    val id: String,
    val name: String,
    val country: String,
    val genre: String,
    val streamUrl: String,
    val website: String
)

data class RadioFilter(val name: String, val country: String, val genre: String)

private fun readRadioFilters(context: Context): List<RadioFilter> {
    val raw = context.getSharedPreferences("tuno_radio", Context.MODE_PRIVATE).getString("custom_filters", "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isNotBlank()) add(RadioFilter(name, item.optString("country", "Все"), item.optString("genre")))
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveRadioFilters(context: Context, filters: List<RadioFilter>) {
    val array = JSONArray()
    filters.forEach { array.put(JSONObject().put("name", it.name).put("country", it.country).put("genre", it.genre)) }
    context.getSharedPreferences("tuno_radio", Context.MODE_PRIVATE).edit().putString("custom_filters", array.toString()).apply()
}

private suspend fun searchRadioBrowser(query: String, countryFilter: String): List<RadioStation> = withContext(Dispatchers.IO) {
    val base = "https://de1.api.radio-browser.info/json/"
    val endpoint = if (query.isBlank() && countryFilter != "Беларусь") "stations/topvote/100" else {
        val params = buildList {
            if (query.isNotBlank()) add("name=" + URLEncoder.encode(query.trim(), "UTF-8"))
            add("limit=100")
            add("hidebroken=true")
            add("order=clickcount")
            add("reverse=true")
            if (countryFilter == "Беларусь") add("countrycode=BY")
        }.joinToString("&")
        "stations/search?$params"
    }
    val connection = (URL(base + endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 12000
        setRequestProperty("User-Agent", "TunoMusic/1.0")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                val name = item.optString("name").trim()
                if (name.isBlank() || !stream.startsWith("http")) continue
                val country = item.optString("country").ifBlank { "Неизвестная страна" }
                val tags = item.optString("tags").split(",").map { it.trim() }.filter { it.isNotBlank() }.take(3)
                val genre = tags.joinToString(" · ").ifBlank { item.optString("codec").ifBlank { "Интернет-радио" } }
                val uuid = item.optString("stationuuid").ifBlank { stream.hashCode().toString() }
                add(RadioStation("rb_$uuid", name, country, genre, stream, item.optString("homepage")))
            }
        }.distinctBy { it.id }
    } finally {
        connection.disconnect()
    }
}

private suspend fun searchAudiusTracks(query: String): List<AudiusTrack> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    // Audius discovery nodes require an app_name identifier and may differ in availability.
    val endpoint = URL("https://discoveryprovider.audius.co/v1/tracks/search?query=$encoded&limit=30&app_name=TunoMusic")
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", "TunoMusic/1.0")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            val details = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Audius search HTTP $status: ${details.take(180)}")
        }
        val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val data = root.optJSONArray("data") ?: JSONArray()
        buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank() || !item.optBoolean("is_streamable", true)) continue
                val user = item.optJSONObject("user")
                val art = item.optJSONObject("artwork")
                val artwork = art?.optString("150x150")?.takeIf { it.startsWith("http") }
                    ?: art?.optString("480x480")?.takeIf { it.startsWith("http") }
                val stream = "https://discoveryprovider.audius.co/v1/tracks/$id/stream?app_name=TunoMusic"
                val downloadable = item.optBoolean("is_downloadable", false)
                val download = "https://discoveryprovider.audius.co/v1/tracks/$id/download?app_name=TunoMusic"
                add(AudiusTrack(id, title, user?.optString("name")?.ifBlank { "Audius" } ?: "Audius",
                    artwork, stream, download, downloadable))
            }
        }.distinctBy { it.id }
    } finally { connection.disconnect() }
}

private suspend fun downloadAudiusTrack(context: Context, track: AudiusTrack): Boolean = withContext(Dispatchers.IO) {
    if (!track.isDownloadable) return@withContext false

    // Try the discovery node first, then Audius' documented API host.
    val urls = listOf(
        track.downloadUrl,
        "https://api.audius.co/v1/tracks/${track.id}/download?app_name=TunoMusic"
    ).distinct()
    val folder = File(context.filesDir, "offline_music").apply { mkdirs() }
    val safeTitle = track.title.replace(Regex("[^A-Za-z0-9А-Яа-я._ -]"), "_")
        .take(70).ifBlank { "Audius_track" }
    val baseName = safeTitle + "_" + track.id.hashCode().toUInt().toString(16)

    for (address in urls) {
        var connection: HttpURLConnection? = null
        val temp = File(folder, "$baseName.part")
        try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 90000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TunoMusic/1.0")
                setRequestProperty("Accept", "audio/*, application/octet-stream;q=0.9, */*;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                temp.delete()
                continue
            }

            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (mime.contains("json") || mime.startsWith("text/") || mime.contains("html")) {
                temp.delete()
                continue
            }

            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                continue
            }

            val extension = when {
                mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
                mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                mime.contains("aac") -> "aac"
                mime.contains("ogg") -> "ogg"
                mime.contains("flac") -> "flac"
                mime.contains("wav") || mime.contains("wave") -> "wav"
                else -> "mp3"
            }
            val target = File(folder, "$baseName.$extension")
            if (target.exists()) target.delete()
            if (temp.renameTo(target)) return@withContext true
            temp.copyTo(target, overwrite = true)
            temp.delete()
            if (target.length() > 0L) return@withContext true
            target.delete()
        } catch (_: Exception) {
            temp.delete()
        } finally {
            connection?.disconnect()
        }
    }
    false
}

private val tunoRadioStations = listOf(
    RadioStation("radius_fm", "Радиус FM", "Беларусь", "Поп · хиты",
        "https://stream2.datacenter.by/radiusfm_main", "https://radiusfm.by/"),
    RadioStation("russian_radio_by", "Русское радио Минск", "Беларусь", "Поп",
        "https://stream.rusradio.by:8443/live128", "https://rusradio.by/"),
    RadioStation("dushevnoe", "Душевное радио", "Беларусь", "Спокойная музыка",
        "https://stream2.datacenter.by/dushevnoe", "https://dushevnoe.by/"),
    RadioStation("alfa_radio", "Альфа Радио", "Беларусь", "Поп · хиты",
        "https://stream.sb.by/alpha", "https://alpha.by/"),
    RadioStation("radio_stalica", "Радио Столица", "Беларусь", "Музыка · новости",
        "https://media2.datacenter.by/stream/stalica/stream", "https://radiostalica.by/"),
    RadioStation("radio_belarus", "Радио Беларусь", "Беларусь", "Новости · разговорное",
        "https://media2.datacenter.by/stream/belarusru/stream", "https://radiobelarus.by/"),
    RadioStation("radio_paradise", "Radio Paradise", "Мир", "Eclectic · rock",
        "https://stream-uk1.radioparadise.com/mp3-128", "https://radioparadise.com/")
)


class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ requires explicit permission for media notifications.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({ runCatching { mediaController = controllerFuture?.get() } },
            ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Night, surface = Panel, primary = Green,
                onBackground = Color.White, onSurface = Color.White
            )) {
                TunoScreen(this, { mediaController }, { loadAudioTracks() })
            }
        }
    }

    private fun loadAudioTracks(): List<AudioTrack> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        val canReadMedia = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("track_edits", Context.MODE_PRIVATE)
        val result = mutableListOf<AudioTrack>()
        val hiddenIds = getSharedPreferences("track_edits", Context.MODE_PRIVATE)
            .getStringSet("hidden_tracks", emptySet()) ?: emptySet()
        if (canReadMedia) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Без названия"
                val artist = cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Неизвестный исполнитель"
                if (id.toString() !in hiddenIds) {
                    result += AudioTrack(id, title, artist, Uri.withAppendedPath(collection, id.toString()),
                        prefs.getString("title_$id", title) ?: title, prefs.getString("cover_$id", null), prefs.getBoolean("favorite_$id", false))
                }
            }
        }
        }
        val offlineFolder = File(filesDir, "offline_music")
        offlineFolder.listFiles()?.filter { it.isFile && it.length() > 0L }?.forEach { file ->
            val id = -((file.name.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L))
            val title = file.nameWithoutExtension.substringBeforeLast('_').ifBlank { file.nameWithoutExtension }
            if (id.toString() !in hiddenIds) {
                result += AudioTrack(id, title, "Офлайн-файл", Uri.fromFile(file),
                    prefs.getString("title_" + id, title) ?: title, prefs.getString("cover_" + id, null),
                    prefs.getBoolean("favorite_" + id, false))
            }
        }
        return result
    }

    override fun onDestroy() {
        mediaController?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun TunoScreen(context: Context, controllerProvider: () -> MediaController?, loadTracks: () -> List<AudioTrack>) {
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var fullPlayer by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var trackToDelete by remember { mutableStateOf<AudioTrack?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchHistory by remember { mutableStateOf(readSearchHistory(context)) }
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var searchPlaylists by rememberSaveable { mutableStateOf(false) }
    var mixSeed by rememberSaveable { mutableIntStateOf((System.currentTimeMillis() / 3600000L).toInt()) }
    var expandedMixTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var offlineMode by rememberSaveable { mutableStateOf(false) }
    var service by rememberSaveable { mutableStateOf("SoundCloud") }
    var audiusResults by remember { mutableStateOf(emptyList<AudiusTrack>()) }
    var audiusLoading by remember { mutableStateOf(false) }
    var audiusError by remember { mutableStateOf<String?>(null) }
    var currentAudiusTrack by remember { mutableStateOf<AudiusTrack?>(null) }
    var audiusDownloading by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(readPlaylists(context)) }
    var cloudFavorites by remember { mutableStateOf(readCloudFavorites(context)) }
    var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var showAddTracks by remember { mutableStateOf(false) }
    var pendingTrackIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingCloudUrls by remember { mutableStateOf(emptySet<String>()) }
    var showCloudPlaylistPicker by remember { mutableStateOf(false) }
    var pendingCloudForNewPlaylist by remember { mutableStateOf<SoundCloudResult?>(null) }
    val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }
    var serviceUrl by rememberSaveable { mutableStateOf("") }
    var soundCloudEmbed by rememberSaveable { mutableStateOf(false) }
    var soundCloudResults by remember { mutableStateOf(emptyList<SoundCloudResult>()) }
    var searchIdle by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var soundCloudLoadFailed by remember { mutableStateOf(false) }
    var soundCloudWebView by remember { mutableStateOf<WebView?>(null) }
    var soundCloudPlaying by remember { mutableStateOf(false) }
    var soundCloudTrack by remember { mutableStateOf<SoundCloudResult?>(null) }
    var soundCloudPosition by remember { mutableLongStateOf(0L) }
    var soundCloudDuration by remember { mutableLongStateOf(0L) }
    var soundCloudQueue by remember { mutableStateOf(emptyList<SoundCloudResult>()) }
    var soundCloudQueueIndex by remember { mutableIntStateOf(0) }
    var playbackQueue by remember { mutableStateOf(emptyList<PlaybackQueueItem>()) }
    var playbackQueueIndex by remember { mutableIntStateOf(0) }
    var radioMood by rememberSaveable { mutableStateOf("Ночное") }
    var radioPending by remember { mutableStateOf(false) }
    var radioMode by rememberSaveable { mutableStateOf(false) }
    var smartDjEnabled by rememberSaveable { mutableStateOf(true) }
    var djFadeRunning by remember { mutableStateOf(false) }
    var radioSelectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var radioNowPlaying by remember { mutableStateOf<RadioStation?>(null) }
    var radioError by remember { mutableStateOf<String?>(null) }
    var radioQuery by rememberSaveable { mutableStateOf("") }
    var radioCountry by rememberSaveable { mutableStateOf("Все") }
    var onlineRadioStations by remember { mutableStateOf(emptyList<RadioStation>()) }
    val radioSelected = radioNowPlaying ?: (tunoRadioStations + onlineRadioStations).firstOrNull { it.id == radioSelectedId }
    var radioFilters by remember { mutableStateOf(readRadioFilters(context)) }
    var selectedRadioFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showRadioFilterDialog by remember { mutableStateOf(false) }
    var radioFilterName by remember { mutableStateOf("") }
    var radioFilterCountry by remember { mutableStateOf("Все") }
    var radioFilterGenre by remember { mutableStateOf("") }
    var radioSearchLoading by remember { mutableStateOf(false) }
    var radioSearchError by remember { mutableStateOf<String?>(null) }
    var radioFavorites by remember {
        mutableStateOf(context.getSharedPreferences("tuno_radio", Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    }
    LaunchedEffect(radioQuery, radioCountry) {
        if (radioCountry == "Избранное") {
            radioSearchLoading = false
            return@LaunchedEffect
        }
        delay(if (radioQuery.isBlank()) 150L else 500L)
        radioSearchLoading = true
        radioSearchError = null
        try {
            onlineRadioStations = searchRadioBrowser(radioQuery, radioCountry)
        } catch (error: Exception) {
            radioSearchError = "Не удалось загрузить каталог. Проверь интернет и попробуй ещё раз."
        } finally {
            radioSearchLoading = false
        }
    }

    val latestCloudTrack by rememberUpdatedState(soundCloudTrack)
    val latestCloudPosition by rememberUpdatedState(soundCloudPosition)
    val latestCloudDuration by rememberUpdatedState(soundCloudDuration)
    val latestCloudPlaying by rememberUpdatedState(soundCloudPlaying)
    val latestCloudWebView by rememberUpdatedState(soundCloudWebView)
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var coverTarget by remember { mutableStateOf<AudioTrack?>(null) }
    val controller = controllerProvider()
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                radioError = "Не удалось запустить поток: " + (error.localizedMessage ?: error.errorCodeName)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) radioError = null
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    fun stopSoundCloudPlayback() {
        soundCloudWebView?.evaluateJavascript("window.tunoPause && window.tunoPause()", null)
        soundCloudPlaying = false
        runCatching {
            context.startService(Intent(context, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_CLOUD_STOP))
        }
    }

    fun playRadioStation(station: RadioStation) {
        val player = controllerProvider() ?: return
        stopSoundCloudPlayback()
        soundCloudEmbed = false
        radioMode = false
        radioPending = false
        playbackQueue = emptyList()
        selectedTrack = null
        radioSelectedId = station.id
        radioNowPlaying = station
        radioError = null
        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist("Tuno Radio · " + station.country)
            .build()
        val itemBuilder = MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.streamUrl)
            .setMediaMetadata(metadata)
        // These stations expose AAC/AAC+ streams without a file extension.
        // Explicit MIME type lets Media3 select its AAC extractor reliably.
        if (station.id == "radius_fm" || station.id == "alfa_radio" || station.id == "radio_stalica") {
            itemBuilder.setMimeType(MimeTypes.AUDIO_AAC)
        }
        val item = itemBuilder.build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }
    val homeMixes = remember(cloudFavorites, soundCloudResults, mixSeed) {
        val favorites = cloudFavorites.distinctBy { it.url }
        val discoveries = soundCloudResults.filterNot { it.isPlaylist }.distinctBy { it.url }
        val allCloud = (favorites + discoveries).distinctBy { it.url }
        buildList {
            if (allCloud.isNotEmpty()) add("Mix для тебя" to allCloud.shuffled(Random(mixSeed)).take(20))
            if (favorites.isNotEmpty()) add("Из SoundCloud-избранного" to favorites.shuffled(Random(mixSeed + 1)).take(20))
            if (discoveries.isNotEmpty()) add("Новые открытия" to discoveries.shuffled(Random(mixSeed + 2)).take(20))
        }.map { (title, tracks) -> title to tracks.map { PlaybackQueueItem(cloud = it) } }
    }

    fun playCloudQueueItem(queue: List<SoundCloudResult>, index: Int) {
        if (queue.isEmpty() || index !in queue.indices) return
        controllerProvider()?.let { player ->
            player.pause()
            player.clearMediaItems()
        }
        soundCloudQueue = queue
        soundCloudQueueIndex = index
        val cloud = queue[index]
        soundCloudTrack = cloud
        soundCloudPosition = 0L
        soundCloudDuration = 0L
        soundCloudPlaying = false
        soundCloudEmbed = true
        // Keep the current tab/screen: playback must not redirect the user to Home/Search.
        val encoded = URLEncoder.encode(cloud.url, "UTF-8")
        val nextUrl = "https://w.soundcloud.com/player/?url=$encoded&color=%239BE7B1&auto_play=true&hide_related=true&show_comments=false&show_user=true&show_reposts=false&visual=true"
        // Reuse the existing SoundCloud widget when possible. Reload the HTML only
        // when the widget has not been created yet; widget.load() switches the
        // actual audio source without destroying the current player instance.
        val existingWeb = soundCloudWebView
        if (existingWeb == null) {
            serviceUrl = nextUrl
        } else {
            val trackJson = JSONObject.quote(cloud.url)
            existingWeb.evaluateJavascript(
                "window.tunoLoad ? (window.tunoLoad($trackJson), true) : false"
            ) { result ->
                if (result != "true") {
                    existingWeb.loadDataWithBaseURL(
                        "https://w.soundcloud.com/",
                        soundCloudWidgetHtml(nextUrl),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
            serviceUrl = nextUrl
        }
    }

    fun playMixedQueueItem(queue: List<PlaybackQueueItem>, index: Int) {
        if (queue.isEmpty() || index !in queue.indices) return
        playbackQueue = queue
        playbackQueueIndex = index
        val item = queue[index]
        if (item.local != null) {
            stopSoundCloudPlayback()
            soundCloudEmbed = false
            selectedTrack = item.local
            soundCloudTrack = null
            controllerProvider()?.let { player -> player.setMediaItem(toMediaItem(item.local)); player.prepare(); player.play() }
        } else {
            if (offlineMode) return
            val cloud = item.cloud ?: return
            val cloudQueue = queue.mapNotNull { it.cloud }
            val cloudIndex = cloudQueue.indexOfFirst { it.url == cloud.url }.coerceAtLeast(0)
            playCloudQueueItem(cloudQueue, cloudIndex)
        }
    }

    fun playNextPlaybackQueueItem() {
        if (playbackQueueIndex + 1 < playbackQueue.size) {
            playMixedQueueItem(playbackQueue, playbackQueueIndex + 1)
        } else if (radioMode && playbackQueue.isNotEmpty()) {
            val nextRadioQueue = playbackQueue.shuffled()
            playbackQueue = nextRadioQueue
            playMixedQueueItem(nextRadioQueue, 0)
        }
    }

    fun playPreviousPlaybackQueueItem() {
        if (playbackQueueIndex > 0) playMixedQueueItem(playbackQueue, playbackQueueIndex - 1)
    }

    fun playNextCloudTrack() {
        if (playbackQueue.isNotEmpty()) playNextPlaybackQueueItem()
        else if (soundCloudQueueIndex + 1 < soundCloudQueue.size) playCloudQueueItem(soundCloudQueue, soundCloudQueueIndex + 1)
    }

    fun playPreviousCloudTrack() {
        if (playbackQueue.isNotEmpty()) playPreviousPlaybackQueueItem()
        else if (soundCloudQueueIndex > 0) playCloudQueueItem(soundCloudQueue, soundCloudQueueIndex - 1)
    }
    val latestPlayNextCloudTrack by rememberUpdatedState { playNextCloudTrack() }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    MusicPlaybackService.ACTION_CLOUD_PLAY -> latestCloudWebView?.evaluateJavascript("window.tunoPlay && window.tunoPlay()", null)
                    MusicPlaybackService.ACTION_CLOUD_PAUSE -> latestCloudWebView?.evaluateJavascript("window.tunoPause && window.tunoPause()", null)
                    MusicPlaybackService.ACTION_CLOUD_NEXT -> latestCloudWebView?.evaluateJavascript("window.tunoNext && window.tunoNext()", null)
                    MusicPlaybackService.ACTION_CLOUD_PREVIOUS -> latestCloudWebView?.evaluateJavascript("window.tunoPrevious && window.tunoPrevious()", null)
                    MusicPlaybackService.ACTION_CLOUD_STOP -> latestCloudWebView?.evaluateJavascript("window.tunoPause && window.tunoPause()", null)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(MusicPlaybackService.ACTION_CLOUD_PLAY)
            addAction(MusicPlaybackService.ACTION_CLOUD_PAUSE)
            addAction(MusicPlaybackService.ACTION_CLOUD_NEXT)
            addAction(MusicPlaybackService.ACTION_CLOUD_PREVIOUS)
            addAction(MusicPlaybackService.ACTION_CLOUD_STOP)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { tracks = loadTracks(); showAddTracks = true }
    }
    fun openAddTracks() {
        pendingTrackIds = emptySet()
        pendingCloudUrls = emptySet()
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            tracks = loadTracks()
            showAddTracks = true
        } else {
            showAddTracks = false
            permissionLauncher.launch(permission)
        }
    }

    fun startSoundCloudSearch(rawQuery: String) {
        service = "SoundCloud"
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isBlank() || offlineMode) return
        query = cleanQuery
        searchFocused = false
        searchHistory = (listOf(cleanQuery) + searchHistory.filterNot { it.equals(cleanQuery, ignoreCase = true) }).take(10)
        saveSearchHistory(context, searchHistory)
        soundCloudEmbed = false
        searchIdle = false
        soundCloudResults = emptyList()
        soundCloudLoadFailed = false
        serviceUrl = "https://soundcloud.com/search/" + (if (searchPlaylists) "sets" else "sounds") + "?q=" + URLEncoder.encode(cleanQuery, "UTF-8")
    }
    fun startAudiusSearch(rawQuery: String) {
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isBlank() || offlineMode) return
        query = cleanQuery
        searchFocused = false
        searchHistory = (listOf(cleanQuery) + searchHistory.filterNot { it.equals(cleanQuery, ignoreCase = true) }).take(10)
        saveSearchHistory(context, searchHistory)
        service = "Audius"
        soundCloudEmbed = false
        searchIdle = false
        serviceUrl = ""
        soundCloudResults = emptyList()
        audiusResults = emptyList()
        audiusError = null
        audiusLoading = true
        scope.launch {
            try {
                audiusResults = searchAudiusTracks(cleanQuery)
                if (audiusResults.isEmpty()) audiusError = "По этому запросу ничего не найдено."
            } catch (error: Exception) {
                audiusError = "Не удалось загрузить Audius. Проверь интернет и попробуй ещё раз."
            } finally { audiusLoading = false }
        }
    }

    fun playAudiusTrack(track: AudiusTrack) {
        currentAudiusTrack = track
        val player = controllerProvider() ?: return
        stopSoundCloudPlayback()
        soundCloudEmbed = false
        soundCloudTrack = null
        radioMode = false
        playbackQueue = emptyList()
        selectedTrack = AudioTrack(
            id = -track.id.hashCode().toLong(),
            originalTitle = track.title,
            originalArtist = track.artist,
            uri = Uri.parse(track.streamUrl),
            customTitle = track.title,
            cover = track.artwork
        )
        val item = MediaItem.Builder().setMediaId("audius_" + track.id).setUri(track.streamUrl)
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setArtworkUri(track.artwork?.let { Uri.parse(it) }).build()).build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun startTunoRadio() {
        if (offlineMode) return
        val moodQuery = when (radioMood) {
            "Спокойное" -> "chill ambient lofi"
            "Энергичное" -> "energetic dance electronic"
            "Грустное" -> "sad indie acoustic"
            else -> "night drive synthwave"
        }
        radioMode = true
        radioPending = true
        searchPlaylists = false
        startSoundCloudSearch(moodQuery)
    }
    val offlineImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var imported = 0
        uris.forEach { uri -> if (importOfflineAudio(context, uri)) imported++ }
        tracks = loadTracks()
        if (imported > 0) {
            android.widget.Toast.makeText(context, "Добавлено в офлайн: " + imported, android.widget.Toast.LENGTH_SHORT).show()
        } else if (uris.isNotEmpty()) {
            android.widget.Toast.makeText(context, "Не удалось импортировать аудио", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = coverTarget
        if (uri != null && target != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).edit().putString("cover_${target.id}", uri.toString()).apply()
            tracks = loadTracks()
            selectedTrack = tracks.firstOrNull { it.id == target.id }
        }
        coverTarget = null
    }

    BackHandler(enabled = fullPlayer || activePlaylistId != null || (tab == 2 && (soundCloudEmbed || !searchIdle))) {
        when {
            fullPlayer -> fullPlayer = false
            tab == 2 && soundCloudEmbed -> {
                soundCloudEmbed = false
                serviceUrl = "https://soundcloud.com/search/sounds?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&t=" + System.currentTimeMillis()
            }
            tab == 2 && !searchIdle -> {
                soundCloudEmbed = false
                searchIdle = true
                serviceUrl = ""
                soundCloudResults = emptyList()
            }
            else -> activePlaylistId = null
        }
    }
    LaunchedEffect(selectedTrack, soundCloudTrack, radioSelectedId, soundCloudEmbed, playing, soundCloudPlaying) {
        val source = when {
            soundCloudEmbed && soundCloudTrack != null -> "cloud"
            radioSelectedId != null || selectedTrack != null -> "media"
            else -> "none"
        }
        val title = radioSelected?.name ?: soundCloudTrack?.title ?: selectedTrack?.customTitle ?: "Tuno Music"
        val artist = when {
            radioSelected != null -> "Tuno Radio · " + radioSelected!!.country
            soundCloudTrack != null -> soundCloudTrack!!.artist
            selectedTrack != null -> selectedTrack!!.originalArtist
            else -> "Твоя музыка"
        }
        val artwork = soundCloudTrack?.artwork ?: selectedTrack?.cover ?: ""
        context.getSharedPreferences("tuno_widget", Context.MODE_PRIVATE).edit()
            .putString("source", source).putString("title", title).putString("artist", artist)
            .putString("artwork", artwork)
            .putBoolean("playing", if (source == "cloud") soundCloudPlaying else playing).apply()
        TunoMusicWidgetProvider.updateAll(context)
    }
    LaunchedEffect(Unit) {
        tracks = loadTracks()
    }
    LaunchedEffect(offlineMode) {
        if (offlineMode) {
            stopSoundCloudPlayback()
            soundCloudEmbed = false
            serviceUrl = ""
            soundCloudResults = emptyList()
        } else if (serviceUrl.isBlank()) {
            searchIdle = false
            val discoveryTerms = listOf("new music", "indie pop", "electronic", "lofi", "alternative rock")
            val term = discoveryTerms[Random.nextInt(discoveryTerms.size)]
            serviceUrl = "https://soundcloud.com/search/sounds?q=" + URLEncoder.encode(term, "UTF-8")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60L * 60L * 1000L)
            mixSeed = (System.currentTimeMillis() / 3600000L).toInt()
        }
    }
    LaunchedEffect(playlists) {
        savePlaylists(context, playlists)
    }
    val latestSearchResults by rememberUpdatedState(soundCloudResults)
    val latestSearchEmbed by rememberUpdatedState(soundCloudEmbed)
    LaunchedEffect(serviceUrl) {
        val requestUrl = serviceUrl
        if (requestUrl.isNotBlank() && !soundCloudEmbed) {
            soundCloudLoadFailed = false
            delay(18000)
            if (latestSearchResults.isEmpty() && !latestSearchEmbed && serviceUrl == requestUrl) {
                soundCloudLoadFailed = true
            }
        }
    }
    LaunchedEffect(soundCloudResults, radioPending) {
        if (radioPending && soundCloudResults.isNotEmpty()) {
            val radioQueue = soundCloudResults.filterNot { it.isPlaylist }
                .distinctBy { it.url }.take(20)
                .map { PlaybackQueueItem(cloud = it) }
            if (radioQueue.isNotEmpty()) {
                radioPending = false
                playMixedQueueItem(radioQueue, 0)
            }
        }
    }
    DisposableEffect(controller, tracks, playbackQueue, playbackQueueIndex) {
        val player = controller
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                if (id != null) selectedTrack = tracks.firstOrNull { it.id == id }
                else if (mediaItem == null) selectedTrack = null
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && playbackQueue.isNotEmpty() && playbackQueue.getOrNull(playbackQueueIndex)?.local != null) {
                    playNextPlaybackQueueItem()
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val current = player?.currentMediaItem ?: return
                val id = current.mediaId.toLongOrNull() ?: return
                val base = tracks.firstOrNull { it.id == id } ?: return
                selectedTrack = base.copy(
                    customTitle = mediaMetadata.title?.toString() ?: base.customTitle,
                    originalArtist = mediaMetadata.artist?.toString() ?: base.originalArtist,
                    cover = mediaMetadata.artworkUri?.toString() ?: base.cover
                )
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { player ->
                playing = player.isPlaying
                if (player.mediaItemCount > 0) {
                    duration = (player.duration.coerceAtLeast(0L) / 1000f).coerceAtLeast(0f)
                    position = (player.currentPosition.coerceAtLeast(0L) / 1000f).coerceAtLeast(0f)
                }
                val currentQueueItem = playbackQueue.getOrNull(playbackQueueIndex)
                if (smartDjEnabled && !soundCloudEmbed && !djFadeRunning && player.isPlaying &&
                    currentQueueItem?.local != null && playbackQueueIndex + 1 < playbackQueue.size &&
                    player.duration > 0L && player.duration - player.currentPosition in 1L..1200L) {
                    djFadeRunning = true
                    val currentId = currentQueueItem.local.id.toString()
                    val startVolume = player.volume.coerceIn(0f, 1f)
                    repeat(6) { step ->
                        delay(100)
                        if (player.currentMediaItem?.mediaId == currentId) {
                            player.volume = startVolume * (1f - (step + 1) / 6f)
                        }
                    }
                    if (player.currentMediaItem?.mediaId == currentId && playbackQueueIndex + 1 < playbackQueue.size) {
                        player.volume = 0f
                        playNextPlaybackQueueItem()
                        repeat(8) { step ->
                            delay(65)
                            player.volume = ((step + 1) / 8f).coerceIn(0f, 1f)
                        }
                    } else {
                        player.volume = startVolume
                    }
                    djFadeRunning = false
                }
            }
            delay(250)
        }
    }

    AnimatedContent(
        targetState = fullPlayer,
        transitionSpec = {
            if (targetState) {
                (fadeIn(tween(260)) +
                    slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 10 } +
                    scaleIn(initialScale = 0.975f, animationSpec = tween(320, easing = FastOutSlowInEasing))) togetherWith
                    (fadeOut(tween(150)) + scaleOut(targetScale = 0.985f, animationSpec = tween(150)))
            } else {
                (fadeIn(tween(220)) +
                    slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 12 }) togetherWith
                    (fadeOut(tween(230)) +
                        slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 14 } +
                        scaleOut(targetScale = 0.975f, animationSpec = tween(240)))
            }
        },
        label = "player_open_close"
    ) { showFullPlayer ->
    if (showFullPlayer) {
        FullPlayerScreen(
            track = selectedTrack,
            cloudTrack = if (soundCloudEmbed) soundCloudTrack else null,
            cloudFavorite = soundCloudTrack?.let { cloud -> cloudFavorites.any { it.url == cloud.url } } == true,
            audiusTrack = currentAudiusTrack,
            playing = if (soundCloudEmbed) soundCloudPlaying else playing,
            position = if (soundCloudEmbed) soundCloudPosition / 1000f else position,
            duration = if (soundCloudEmbed) soundCloudDuration / 1000f else duration,
            onBack = { fullPlayer = false },
            onAddToPlaylist = { showCloudPlaylistPicker = true },
            onDownload = {
                val track = currentAudiusTrack
                if (track != null && track.isDownloadable && !audiusDownloading) scope.launch {
                    audiusDownloading = true
                    val saved = downloadAudiusTrack(context, track)
                    if (saved) {
                        tracks = loadTracks()
                        Toast.makeText(context, "Трек сохранён офлайн", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, "Не удалось скачать трек", Toast.LENGTH_SHORT).show()
                    audiusDownloading = false
                }
            },
            onPlayPause = {
                if (soundCloudEmbed) soundCloudWebView?.evaluateJavascript("window.tunoToggle && window.tunoToggle()", null)
                else controllerProvider()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
            },
            onNext = {
                if (playbackQueue.isNotEmpty()) playNextPlaybackQueueItem()
                else if (soundCloudEmbed) playNextCloudTrack()
                else controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            },
            onPrevious = {
                if (playbackQueue.isNotEmpty()) playPreviousPlaybackQueueItem()
                else if (soundCloudEmbed) playPreviousCloudTrack()
                else controllerProvider()?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
            },
            onSeek = {
                if (soundCloudEmbed && soundCloudDuration > 0L) {
                    val target = (it * 1000).toLong().coerceIn(0L, soundCloudDuration)
                    soundCloudWebView?.evaluateJavascript("window.tunoSeekTo && window.tunoSeekTo($target)", null)
                } else if (!soundCloudEmbed) controllerProvider()?.seekTo((it * 1000).toLong())
            },
            onCover = { selectedTrack?.let { coverTarget = it; coverPicker.launch(arrayOf("image/*")) } },
            onRename = { selectedTrack?.let { editingTrack = it; editTitle = it.customTitle } },
            onFavorite = {
                val cloud = soundCloudTrack
                if (soundCloudEmbed && cloud != null) {
                    cloudFavorites = if (cloudFavorites.any { it.url == cloud.url }) cloudFavorites.filterNot { it.url == cloud.url }
                        else cloudFavorites + cloud
                    saveCloudFavorites(context, cloudFavorites)
                } else selectedTrack?.let { track ->
                    val value = !track.isFavorite
                    context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).edit().putBoolean("favorite_${track.id}", value).apply()
                    updateFavoriteOrder(context, track.id, value)
                    tracks = loadTracks()
                    selectedTrack = tracks.firstOrNull { it.id == track.id }
                }
            }
        )
    } else {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17251D), Night, Night)))) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TUNO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text(when (tab) { 0 -> "Твоя музыка"; 1 -> "Избранное"; 2 -> "Дом"; 4 -> "Радио"; else -> "Плейлисты" }, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { offlineMode = !offlineMode }) {
                        Icon(if (offlineMode) Icons.Default.CloudOff else Icons.Default.CloudQueue,
                            if (offlineMode) "Офлайн-режим включён" else "Офлайн-режим выключен",
                            tint = if (offlineMode) Green else Muted, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (tab == 0 || tab == 1) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск в медиатеке", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) }, singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab == 1) "Избранные треки" else "Медиатека", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${tracks.size} треков", color = Muted, fontSize = 12.sp)
                    }
                    val filteredBase = tracks.filter { (tab != 1 || it.isFavorite) && (it.customTitle.contains(query, true) || it.originalArtist.contains(query, true)) }
                    val favoriteOrder = runCatching {
                        val saved = JSONArray(context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).getString("favorite_order", "[]") ?: "[]")
                        buildList { for (i in 0 until saved.length()) add(saved.optLong(i)) }
                    }.getOrDefault(emptyList())
                    val filtered = if (tab == 1) filteredBase.sortedBy {
                        val index = favoriteOrder.indexOf(it.id)
                        if (index < 0) Int.MAX_VALUE else index
                    } else filteredBase
                    val visibleCloudFavorites = cloudFavorites.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                    val currentListQueue = filtered.map { PlaybackQueueItem(local = it) } +
                        (if (tab == 1) visibleCloudFavorites.map { PlaybackQueueItem(cloud = it) } else emptyList())
                    if (tracks.isEmpty() && !(tab == 1 && cloudFavorites.isNotEmpty())) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Green, modifier = Modifier.size(52.dp))
                            Text("Музыка пока не найдена", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Разреши доступ к аудиофайлам телефона", color = Muted, fontSize = 12.sp)
                            Button(onClick = { permissionLauncher.launch(permission) }) { Text("Открыть медиатеку") }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                            items(filtered, key = { it.id }) { track ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    playMixedQueueItem(currentListQueue, currentListQueue.indexOfFirst { it.local?.id == track.id }.coerceAtLeast(0))
                                }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CoverThumb(track.cover, 48)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.customTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text(track.originalArtist, color = Muted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            Icon(Icons.Default.MoreVert, null, tint = Muted)
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Переименовать") },
                                                onClick = { menuExpanded = false; editingTrack = track; editTitle = track.customTitle },
                                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Добавить в плейлист") },
                                                onClick = { menuExpanded = false; trackToAddToPlaylist = track },
                                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Удалить из медиатеки") },
                                                onClick = { menuExpanded = false; trackToDelete = track },
                                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                                            )
                                        }
                                    }
                                }
                            }
                            if (tab == 1) {
                                items(visibleCloudFavorites, key = { "cloud-fav-" + it.url }) { cloud ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        playMixedQueueItem(currentListQueue, currentListQueue.indexOfFirst { it.cloud?.url == cloud.url })
                                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (cloud.artwork != null) AsyncImage(model = cloud.artwork, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) else CoverThumb(null, 48)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(cloud.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("SoundCloud · ${cloud.artist}", color = Muted, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { cloudFavorites = cloudFavorites.filterNot { it.url == cloud.url }; saveCloudFavorites(context, cloudFavorites) }) {
                                            Icon(Icons.Default.Favorite, null, tint = Green)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF111915), RoundedCornerShape(18.dp))
                            .border(1.dp, Color(0xFF26352C), RoundedCornerShape(18.dp))
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (soundCloudEmbed || !searchIdle) {
                            IconButton(onClick = {
                                soundCloudEmbed = false
                                searchIdle = true
                                serviceUrl = ""
                                soundCloudResults = emptyList()
                            }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            }
                        } else {
                            Box(
                                Modifier.size(38.dp).background(Color(0xFF20372A), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(service.uppercase(), color = Muted, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                            Text(if (soundCloudEmbed) "Плеер Tuno" else "Поиск музыки",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Surface(color = Color(0xFF20372A), shape = RoundedCornerShape(50)) {
                            Text("TUNO", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().height(52.dp).align(Alignment.CenterHorizontally)
                            .onFocusChanged { searchFocused = it.isFocused }, singleLine = true,
                        enabled = !offlineMode,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text(if (offlineMode) "Офлайн-режим" else "Трек, исполнитель или альбом", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        trailingIcon = { IconButton(onClick = { if (service == "Audius") startAudiusSearch(query) else startSoundCloudSearch(query) }) {
                            Icon(Icons.Default.ArrowForward, null, tint = Green)
                        } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedContainerColor = Color(0xFF111915), unfocusedContainerColor = Color(0xFF111915),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = service == "SoundCloud", onClick = { service = "SoundCloud"; searchIdle = true; soundCloudEmbed = false; serviceUrl = ""; audiusResults = emptyList() }, label = { Text("SoundCloud") })
                        FilterChip(selected = service == "Audius", onClick = { service = "Audius"; searchIdle = true; soundCloudEmbed = false; serviceUrl = ""; soundCloudResults = emptyList() }, label = { Text("Audius") })
                    }
                    if (service == "SoundCloud") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !searchPlaylists, onClick = { searchPlaylists = false }, label = { Text("Треки") })
                            FilterChip(selected = searchPlaylists, onClick = { searchPlaylists = true }, label = { Text("Плейлисты") })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if ((query.isBlank() || searchFocused) && !soundCloudEmbed) {
                        if (searchFocused && searchHistory.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("История поиска", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    searchHistory = emptyList()
                                    saveSearchHistory(context, searchHistory)
                                }) { Text("Очистить", color = Muted, fontSize = 12.sp) }
                            }
                            searchHistory.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.forEach { historyQuery ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { startSoundCloudSearch(historyQuery) }
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.History, null, tint = Muted, modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(historyQuery, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                    Icon(Icons.Default.NorthWest, null, tint = Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (offlineMode) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Офлайн-музыка", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(tracks.size.toString() + " треков · качество Original", color = Muted, fontSize = 11.sp)
                                }
                                Button(onClick = { offlineImportPicker.launch(arrayOf("audio/*")) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244B32))) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Импорт")
                                }
                            }
                            if (tracks.isEmpty()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CloudOff, null, tint = Green, modifier = Modifier.size(42.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Пока нет офлайн-музыки", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Original · файлы сохраняются без перекодирования", color = Muted, fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(tracks, key = { "offline-" + it.id }) { track ->
                                        Row(Modifier.fillMaxWidth().clickable {
                                            val queue = tracks.map { PlaybackQueueItem(local = it) }
                                            playMixedQueueItem(queue, tracks.indexOfFirst { it.id == track.id })
                                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            CoverThumb(track.cover, 48)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(track.customTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(track.originalArtist, color = Muted, fontSize = 12.sp)
                                            }
                                            Box {
                                                IconButton(onClick = { trackToDelete = track }) {
                                                    Icon(Icons.Default.MoreVert, "Действия", tint = Muted)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (query.isBlank() && !offlineMode) {
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 14.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF1D3828), Color(0xFF111915))))
                                .border(1.dp, Color(0xFF2C4936), RoundedCornerShape(20.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("TUNO RADIO", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                    Text("Музыка под настроение", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                                TunoVisualizer(isPlaying = playing || soundCloudPlaying)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("Спокойное", "Энергичное", "Грустное", "Ночное").forEach { mood ->
                                    FilterChip(
                                        selected = radioMood == mood,
                                        onClick = { radioMood = mood },
                                        label = { Text(mood, fontSize = 10.sp, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Smart DJ", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Плавные переходы для офлайн-треков", color = Muted, fontSize = 10.sp)
                                }
                                Switch(checked = smartDjEnabled, onCheckedChange = { smartDjEnabled = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (radioPending) "Ищем музыку…" else if (radioMode) "Радио играет · следующий трек подберётся автоматически" else "Персональное радио из открытых треков SoundCloud",
                                    color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (radioMode) {
                                            radioMode = false
                                            radioPending = false
                                        } else startTunoRadio()
                                    },
                                    enabled = !offlineMode && !radioPending,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF315C40))
                                ) {
                                    Icon(if (radioMode) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (radioMode) "Стоп" else "Радио")
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Твои Mix", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text("Автоматические подборки только из SoundCloud", color = Muted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { mixSeed = (System.currentTimeMillis() / 1000L).toInt() }) {
                                Icon(Icons.Default.Shuffle, "Обновить Mix", tint = Green)
                            }
                        }
                        if (homeMixes.isEmpty()) {
                            Text("Добавь треки в SoundCloud-избранное или найди музыку через поиск — здесь появятся Mix.",
                                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                                items(homeMixes, key = { it.first }) { mix ->
                                    val mixTitle = mix.first
                                    val mixQueue = mix.second
                                    Column(Modifier.width(286.dp).padding(bottom = 12.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                                .background(Brush.linearGradient(listOf(Color(0xFF244B32), Color(0xFF111915))))
                                                .clickable { expandedMixTitle = if (expandedMixTitle == mixTitle) null else mixTitle }
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                                                .background(Color(0xFF315C40)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.AutoAwesome, null, tint = Green, modifier = Modifier.size(27.dp))
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(mixTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(mixQueue.size.toString() + " треков · SoundCloud", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                            }
                                            IconButton(onClick = { playMixedQueueItem(mixQueue, 0) }) {
                                                Icon(Icons.Default.PlayCircleFilled, "Воспроизвести Mix", tint = Green, modifier = Modifier.size(31.dp))
                                            }
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                                            items(if (expandedMixTitle == mixTitle) mixQueue else mixQueue.take(3),
                                                key = { it.cloud?.url ?: ("local-" + it.local?.id) }) { item ->
                                                val cloud = item.cloud ?: return@items
                                                Column(Modifier.width(138.dp).clip(RoundedCornerShape(14.dp))
                                                    .background(Panel).clickable {
                                                        expandedMixTitle = mixTitle
                                                        val index = mixQueue.indexOfFirst { it.cloud?.url == cloud.url }
                                                        playMixedQueueItem(mixQueue, index)
                                                    }.padding(9.dp)) {
                                                    CoverThumb(cloud.artwork, 112)
                                                    Text(cloud.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                                                    Text(cloud.artist, color = Muted, maxLines = 1, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                        if (mixQueue.size > 3) {
                                            TextButton(onClick = { expandedMixTitle = if (expandedMixTitle == mixTitle) null else mixTitle }) {
                                                Text(if (expandedMixTitle == mixTitle) "Свернуть" else "Все " + mixQueue.size + " треков", color = Green)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        }
                    }
                    if (searchIdle && !offlineMode) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.TravelExplore, null, tint = Green, modifier = Modifier.size(54.dp))
                            Text("Поиск " + service, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Введи название трека или исполнителя", color = Muted, fontSize = 12.sp)
                        }
                    } else if (offlineMode) {
                        // Offline library is rendered above; no network content here.
                    } else if (service == "Audius") {
                        when {
                            audiusLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
                            audiusError != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(audiusError ?: "", color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                TextButton(onClick = { startAudiusSearch(query) }) { Text("Повторить", color = Green) }
                            }
                            audiusResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Введи запрос, чтобы найти музыку в Audius", color = Muted) }
                            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
                                if (currentAudiusTrack != null) {
                                    item(key = "audius-inline-player") {
                                        AudiusPlayerCard(
                                            track = currentAudiusTrack!!,
                                            playing = playing,
                                            position = position,
                                            duration = duration,
                                            downloading = audiusDownloading,
                                            onPlayPause = {
                                                controllerProvider()?.let { player ->
                                                    if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
                                                }
                                            },
                                            onSeek = { value -> controllerProvider()?.seekTo((value * 1000f).toLong()) },
                                            onDownload = {
                                                val selected = currentAudiusTrack!!
                                                if (selected.isDownloadable && !audiusDownloading) scope.launch {
                                                    audiusDownloading = true
                                                    val saved = downloadAudiusTrack(context, selected)
                                                    if (saved) {
                                                        tracks = loadTracks()
                                                        Toast.makeText(context, "Трек сохранён офлайн", Toast.LENGTH_SHORT).show()
                                                    } else Toast.makeText(context, "Не удалось скачать трек", Toast.LENGTH_SHORT).show()
                                                    audiusDownloading = false
                                                }
                                            }
                                        )
                                    }
                                }
                                items(audiusResults, key = { "audius-" + it.id }) { track ->
                                    Row(Modifier.fillMaxWidth().clickable { playAudiusTrack(track) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CoverThumb(track.artwork, 52)
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text(track.artist, color = Muted, fontSize = 11.sp, maxLines = 1)
                                            Text(if (track.isDownloadable) "Автор разрешил скачивание" else "Только онлайн-прослушивание", color = if (track.isDownloadable) Green else Muted, fontSize = 10.sp)
                                        }
                                        IconButton(onClick = { playAudiusTrack(track) }) { Icon(Icons.Default.PlayArrow, null, tint = Green) }
                                    }
                                }
                            }
                        }
                    } else if (serviceUrl.isBlank()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.TravelExplore, null, tint = Green, modifier = Modifier.size(54.dp))
                            Text("Поиск музыки в SoundCloud", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Введи запрос выше, чтобы найти треки", color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Night)
                                    .border(1.dp, Color(0xFF26352C), RoundedCornerShape(20.dp))
                                    .alpha(if (soundCloudEmbed || (soundCloudResults.isEmpty() && soundCloudLoadFailed)) 1f else 0f),
                                factory = { ctx ->
                                    (soundCloudWebView ?: WebView(ctx)).apply {
                                        soundCloudWebView = this
                                        setBackgroundColor(android.graphics.Color.rgb(8, 11, 10))
                                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                                        isVerticalScrollBarEnabled = false
                                        isHorizontalScrollBarEnabled = false
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.mediaPlaybackRequiresUserGesture = false
                                        settings.loadsImagesAutomatically = true
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        settings.setSupportMultipleWindows(false)
                                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        webChromeClient = android.webkit.WebChromeClient()
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        addJavascriptInterface(SoundCloudWidgetBridge(
                                            onPlayback = { isPlaying ->
                                                context.mainExecutor.execute {
                                                    soundCloudPlaying = isPlaying
                                                    val cloud = latestCloudTrack
                                                    if (cloud != null) {
                                                        val action = if (isPlaying) MusicPlaybackService.ACTION_CLOUD_PLAY else MusicPlaybackService.ACTION_CLOUD_PAUSE
                                                        val intent = Intent(context, MusicPlaybackService::class.java).setAction(action)
                                                            .putExtra(MusicPlaybackService.EXTRA_TITLE, cloud.title)
                                                            .putExtra(MusicPlaybackService.EXTRA_ARTIST, cloud.artist)
                                                            .putExtra(MusicPlaybackService.EXTRA_ARTWORK, cloud.artwork ?: "")
                                                            .putExtra(MusicPlaybackService.EXTRA_DURATION, latestCloudDuration)
                                                            .putExtra(MusicPlaybackService.EXTRA_POSITION, latestCloudPosition)
                                                        if (isPlaying) ContextCompat.startForegroundService(context, intent)
                                                        else context.startService(intent)
                                                    }
                                                }
                                            },
                                            onProgress = { current, total ->
                                                context.mainExecutor.execute {
                                                    soundCloudPosition = current.coerceAtLeast(0L)
                                                    soundCloudDuration = total.coerceAtLeast(0L)
                                                    val cloud = latestCloudTrack
                                                    if (cloud != null && latestCloudPlaying && current / 1000L != (current - 800L).coerceAtLeast(0L) / 1000L) {
                                                        val intent = Intent(context, MusicPlaybackService::class.java)
                                                            .setAction(MusicPlaybackService.ACTION_CLOUD_UPDATE)
                                                            .putExtra(MusicPlaybackService.EXTRA_TITLE, cloud.title)
                                                            .putExtra(MusicPlaybackService.EXTRA_ARTIST, cloud.artist)
                                                            .putExtra(MusicPlaybackService.EXTRA_ARTWORK, cloud.artwork ?: "")
                                                            .putExtra(MusicPlaybackService.EXTRA_DURATION, total)
                                                            .putExtra(MusicPlaybackService.EXTRA_POSITION, current)
                                                            .putExtra(MusicPlaybackService.EXTRA_PLAYING, true)
                                                        context.startService(intent)
                                                    }
                                                }
                                            },
                                            onFinished = { context.mainExecutor.execute { latestPlayNextCloudTrack() } }
                                        ), "TunoWidget")
                                        addJavascriptInterface(SoundCloudBridge { raw ->
                                            context.mainExecutor.execute {
                                                runCatching {
                                                    val array = JSONArray(raw)
                                                    val parsed = buildList {
                                                        for (i in 0 until array.length()) {
                                                            val item = array.getJSONObject(i)
                                                            val url = item.optString("url")
                                                            val title = item.optString("title")
                                                            if (url.isNotBlank() && title.isNotBlank()) {
                                                                add(SoundCloudResult(title, item.optString("artist"), url,
                                                                    item.optString("artwork").takeIf { it.startsWith("http") }, item.optBoolean("isPlaylist")))
                                                            }
                                                        }
                                                    }
                                                    if (parsed.isNotEmpty()) {
                                                        soundCloudResults = (soundCloudResults + parsed).distinctBy { it.url }.take(30)
                                                        soundCloudLoadFailed = false
                                                    }
                                                }
                                            }
                                        }, "TunoBridge")
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                                val uri = request.url
                                                val host = uri.host?.lowercase().orEmpty()
                                                val path = uri.path.orEmpty()
                                                val reserved = listOf("/search/", "/discover", "/you", "/stream", "/upload",
                                                    "/signin", "/signup", "/settings", "/pages", "/charts", "/popular", "/stations")
                                                val isSoundPage = (host == "soundcloud.com" || host == "www.soundcloud.com") &&
                                                    path.count { it == '/' } >= 2 && reserved.none { path.startsWith(it) }
                                                if (request.isForMainFrame && isSoundPage) {
                                                    val encoded = URLEncoder.encode(uri.toString(), "UTF-8")
                                                    soundCloudEmbed = true
                                                    view.loadUrl("https://w.soundcloud.com/player/?url=$encoded&color=%239BE7B1&auto_play=true&hide_related=true&show_comments=false&show_user=true&show_reposts=false&visual=true")
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onPageFinished(view: WebView, url: String) {
                                                super.onPageFinished(view, url)
                                                if (url.contains("soundcloud.com/search")) {
                                                    val script = """
                                                        (function() {
                                                          if (window.__tunoObserver) window.__tunoObserver.disconnect();
                                                          if (window.__tunoPoll) clearInterval(window.__tunoPoll);
                                                          var seen = (window.__tunoSeen = Object.create(null));
                                                           var playlistMode = location.pathname.indexOf('/search/sets') >= 0;
                                                          var timer = null;
                                                          function textOf(node) {
                                                            return node ? (node.innerText || node.textContent || "").replace(/\s+/g, " ").trim() : "";
                                                          }
                                                          function titleFrom(anchor, parts) {
                                                            var selectors = [
                                                              '[data-testid*="title"]',
                                                              '.soundTitle__title',
                                                              '[class*="soundTitle__title"]',
                                                              '[class*="trackTitle"]',
                                                              '[class*="title"]'
                                                            ];
                                                            var nodes = [anchor, anchor.parentElement,
                                                              anchor.closest('[class*="soundList__item"]'),
                                                              anchor.closest('[class*="searchList"]'),
                                                              anchor.closest('li')];
                                                            for (var n = 0; n < nodes.length; n++) {
                                                              var card = nodes[n];
                                                              if (!card) continue;
                                                              for (var j = 0; j < selectors.length; j++) {
                                                                var titleNode = card.querySelector(selectors[j]);
                                                                var candidate = textOf(titleNode);
                                                                if (candidate && candidate.length >= 2 && candidate.length <= 160) return candidate;
                                                              }
                                                            }
                                                            var direct = textOf(anchor) || anchor.getAttribute("aria-label") || anchor.getAttribute("title") || "";
                                                            direct = direct.replace(/^play\s+/i, "").trim();
                                                            if (direct.length >= 2 && direct.length <= 160) return direct;
                                                            return decodeURIComponent(parts[2] || parts[1]).replace(/[-_]+/g, " ").replace(/\s+/g, " ").trim();
                                                          }
                                                          function collect() {
                                                            timer = null;
                                                            var found = [];
                                                            var links = document.querySelectorAll('a[href*="soundcloud.com/"], a[href^="/"]');
                                                            for (var i = 0; i < links.length; i++) {
                                                              var a = links[i];
                                                              try {
                                                                var u = new URL(a.href, location.href);
                                                                var host = u.hostname.toLowerCase();
                                                                if (host !== "soundcloud.com" && host !== "www.soundcloud.com" && host !== "m.soundcloud.com") continue;
                                                                var parts = u.pathname.split("/").filter(Boolean);
                                                                var isPlaylist = parts.length >= 3 && parts[1] === "sets";
                                                                if (playlistMode) {
                                                                  if (!isPlaylist) continue;
                                                                } else {
                                                                  if (parts.length !== 2) continue;
                                                                  if (/^(search|discover|you|stream|charts|popular|stations|sets|tags|pages|upload|signin|signup)$/i.test(parts[0])) continue;
                                                                }
                                                                var clean = isPlaylist
                                                                  ? "https://soundcloud.com/" + parts[0] + "/sets/" + parts[2]
                                                                  : "https://soundcloud.com/" + parts[0] + "/" + parts[1];
                                                                if (seen[clean]) continue;
                                                                var title = titleFrom(a, parts);
                                                                if (!title || title.length < 2 || title.length > 160) continue;
                                                                var card = a.closest('[class*="soundList__item"]') ||
                                                                  a.closest('[class*="searchList"]') || a.closest('li') || a.parentElement || a;
                                                                var artistNode = card && card.querySelector('.soundTitle__username, [class*="username"], [data-testid*="username"]');
                                                                var img = card && card.querySelector("img");
                                                                var artist = textOf(artistNode) || decodeURIComponent(parts[0]).replace(/[-_]+/g, " ");
                                                                seen[clean] = true;
                                                                found.push({title:title, artist:artist, url:clean,
                                                                  artwork:img ? (img.currentSrc || img.src || "") : "", isPlaylist:isPlaylist});
                                                              } catch(e) {}
                                                            }
                                                            if (found.length && window.TunoBridge) {
                                                              window.TunoBridge.onResults(JSON.stringify(found.slice(0,30)));
                                                            }
                                                          }
                                                          function scheduleCollect() {
                                                            if (timer !== null) return;
                                                            timer = setTimeout(collect, 300);
                                                          }
                                                          collect();
                                                          window.__tunoObserver = new MutationObserver(scheduleCollect);
                                                          if (document.body) window.__tunoObserver.observe(document.body, {childList:true,subtree:true});
                                                          window.__tunoPoll = setInterval(function() {
                                                            collect();
                                                            if (Date.now() - window.__tunoStartedAt > 45000) {
                                                              clearInterval(window.__tunoPoll);
                                                              if (window.__tunoObserver) window.__tunoObserver.disconnect();
                                                            }
                                                          }, 700);
                                                          window.__tunoStartedAt = Date.now();
                                                        })();
                                                    """.trimIndent()
                                                    view.evaluateJavascript(script, null)
                                                }
                                            }
                                        }
                                        if (tag != serviceUrl) {
                                            tag = serviceUrl
                                            if (soundCloudEmbed) {
                                                loadDataWithBaseURL("https://w.soundcloud.com/", soundCloudWidgetHtml(serviceUrl),
                                                    "text/html", "UTF-8", null)
                                            } else {
                                                loadUrl(serviceUrl)
                                            }
                                        }
                                    }
                                },
                                update = { web ->
                                    if (web.tag != serviceUrl) {
                                        web.tag = serviceUrl
                                        if (soundCloudEmbed) {
                                            web.loadDataWithBaseURL("https://w.soundcloud.com/", soundCloudWidgetHtml(serviceUrl),
                                                "text/html", "UTF-8", null)
                                        } else {
                                            web.loadUrl(serviceUrl)
                                        }
                                    }
                                }
                            )
                            if (soundCloudEmbed) {
                                Row(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .padding(12.dp).background(Color(0xEE111915), RoundedCornerShape(18.dp))
                                        .border(1.dp, Color(0xFF344139), RoundedCornerShape(18.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.GraphicEq, null, tint = Green)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Плеер SoundCloud", color = Color.White, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        soundCloudWebView?.evaluateJavascript("window.tunoToggle && window.tunoToggle()", null)
                                    }) {
                                        Icon(if (soundCloudPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            "Воспроизведение", tint = Green, modifier = Modifier.size(30.dp))
                                    }
                                }
                            }
                            if (!soundCloudEmbed) {
                                if (soundCloudResults.isEmpty()) {
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .then(if (soundCloudLoadFailed) Modifier.align(Alignment.TopCenter).background(Night.copy(alpha = 0.94f)).padding(12.dp) else Modifier.fillMaxHeight()),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = if (soundCloudLoadFailed) Arrangement.Center else Arrangement.Center
                                    ) {
                                        if (soundCloudLoadFailed) {
                                            Icon(Icons.Default.CloudOff, null, tint = Muted, modifier = Modifier.size(30.dp))
                                            Spacer(Modifier.height(6.dp))
                                            Text("Не удалось извлечь список треков", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Открыл страницу SoundCloud ниже — попробуй выбрать трек там", color = Muted, fontSize = 11.sp)
                                            Spacer(Modifier.height(6.dp))
                                            Button(onClick = {
                                                val q = URLEncoder.encode(query.trim(), "UTF-8")
                                                soundCloudResults = emptyList()
                                                soundCloudLoadFailed = false
                                                serviceUrl = "https://soundcloud.com/search/" + (if (searchPlaylists) "sets" else "sounds") + "?q=$q&retry=" + System.currentTimeMillis()
                                            }) { Text("Повторить поиск") }
                                        } else {
                                            CircularProgressIndicator(color = Green, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.height(12.dp))
                                            Text("Ищем прямо в SoundCloud…", color = Muted, fontSize = 13.sp)
                                            Text("Если SoundCloud не ответит за 18 секунд, появится повтор", color = Muted, fontSize = 11.sp)
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                                            .background(Night).border(1.dp, Color(0xFF26352C), RoundedCornerShape(18.dp)),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        items(soundCloudResults, key = { it.url }) { result ->
                                            var cloudMenu by remember { mutableStateOf(false) }
                                            val cloudIsFavorite = cloudFavorites.any { it.url == result.url }
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    playbackQueue = emptyList()
                                                    playCloudQueueItem(listOf(result), 0)
                                                }.padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (result.artwork != null) {
                                                    AsyncImage(model = result.artwork, contentDescription = null,
                                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                                                } else {
                                                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Panel),
                                                        contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Default.MusicNote, null, tint = Green)
                                                    }
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(result.title, color = Color.White, maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                                    Text((if (result.isPlaylist) "Плейлист · " else "") + result.artist.ifBlank { "SoundCloud" }, color = Muted, fontSize = 12.sp,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Icon(if (result.isPlaylist) Icons.Default.QueueMusic else Icons.Default.PlayCircle, null, tint = Green, modifier = Modifier.size(28.dp))
                                                Box {
                                                    IconButton(onClick = { cloudMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = Muted) }
                                                    DropdownMenu(expanded = cloudMenu, onDismissRequest = { cloudMenu = false }) {
                                                        DropdownMenuItem(
                                                            text = { Text(if (cloudIsFavorite) "Убрать из избранного" else "В избранное") },
                                                            leadingIcon = { Icon(Icons.Default.Favorite, null) },
                                                            onClick = {
                                                                cloudFavorites = if (cloudIsFavorite) cloudFavorites.filterNot { it.url == result.url }
                                                                    else (cloudFavorites + result).distinctBy { it.url }
                                                                saveCloudFavorites(context, cloudFavorites)
                                                                cloudMenu = false
                                                            }
                                                        )
                                                         playlists.forEach { playlist ->
                                                             DropdownMenuItem(text = { Text("В «${playlist.name}»") }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                                                 onClick = {
                                                                     val updated = playlist.copy(soundCloudTracks = (playlist.soundCloudTracks + result).distinctBy { it.url })
                                                                     playlists = playlists.map { if (it.id == updated.id) updated else it }
                                                                     savePlaylists(context, playlists)
                                                                     cloudMenu = false
                                                                 })
                                                         }
                                                         DropdownMenuItem(text = { Text("Создать плейлист") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                                             onClick = { cloudMenu = false; pendingCloudForNewPlaylist = result; tab = 3; playlistName = ""; showCreatePlaylist = true })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 4) {
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("TUNO RADIO", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text("Слушай в прямом эфире", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                            Icon(Icons.Default.Radio, null, tint = Green, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = radioQuery,
                            onValueChange = { radioQuery = it; selectedRadioFilter = null },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            singleLine = true,
                            placeholder = { Text("Найти радиостанцию", color = Muted) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                                focusedContainerColor = Color(0xFF111915), unfocusedContainerColor = Color(0xFF111915),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green
                            )
                        )
                        Spacer(Modifier.height(9.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                            items(listOf("Все", "Беларусь", "Мир", "Избранное")) { category ->
                                FilterChip(selected = selectedRadioFilter == null && radioCountry == category,
                                    onClick = { radioCountry = category; selectedRadioFilter = null },
                                    label = { Text(category, fontSize = 11.sp) })
                            }
                            items(radioFilters, key = { "filter_" + it.name }) { filter ->
                                FilterChip(selected = selectedRadioFilter == filter.name,
                                    onClick = { selectedRadioFilter = filter.name },
                                    label = { Text(filter.name, fontSize = 11.sp) },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            radioFilters = radioFilters.filterNot { it.name == filter.name }
                                            saveRadioFilters(context, radioFilters)
                                            if (selectedRadioFilter == filter.name) selectedRadioFilter = null
                                        }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, "Удалить фильтр", tint = Muted, modifier = Modifier.size(13.dp))
                                        }
                                    })
                            }
                            item { AssistChip(onClick = {
                                radioFilterName = ""; radioFilterCountry = "Все"; radioFilterGenre = ""; showRadioFilterDialog = true
                            }, label = { Text("Добавить фильтр", fontSize = 11.sp) }, leadingIcon = {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(15.dp))
                            }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (radioSelected != null) {
                            val currentRadio = radioSelected!!
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF244B32), Color(0xFF111915))))
                                    .border(1.dp, Color(0xFF2C4936), RoundedCornerShape(18.dp))
                                    .padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF315C40)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Radio, null, tint = Green, modifier = Modifier.size(27.dp))
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (playing) "СЕЙЧАС ИГРАЕТ" else "РАДИО НА ПАУЗЕ", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Text(currentRadio.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(currentRadio.country + " · " + currentRadio.genre, color = Muted, fontSize = 10.sp)
                                    Text("Текущая радиостанция", color = Muted, fontSize = 9.sp)
                                }
                                IconButton(onClick = {
                                    controllerProvider()?.let { player ->
                                        if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
                                    }
                                }) {
                                    Icon(if (playing) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                                        "Пауза или воспроизведение", tint = Green, modifier = Modifier.size(34.dp))
                                }
                                IconButton(onClick = {
                                    controllerProvider()?.stop()
                                    radioSelectedId = null
                                    radioNowPlaying = null
                                    radioError = null
                                }) { Icon(Icons.Default.StopCircle, "Остановить радио", tint = Muted, modifier = Modifier.size(28.dp)) }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (radioError != null && radioSelected != null) {
                            Text(
                                radioError!! + " Проверь интернет или попробуй другую станцию.",
                                color = Color(0xFFFF9B8F), fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        val allRadioStations = (tunoRadioStations + onlineRadioStations).distinctBy { it.id }
                        val activeCustomFilter = radioFilters.firstOrNull { it.name == selectedRadioFilter }
                        val visibleStations = allRadioStations.filter { station ->
                            if (activeCustomFilter != null) {
                                (activeCustomFilter.country == "Все" || station.country.equals(activeCustomFilter.country, true)) &&
                                    (activeCustomFilter.genre.isBlank() || station.genre.contains(activeCustomFilter.genre, true) || station.name.contains(activeCustomFilter.genre, true))
                            } else when (radioCountry) {
                                "Беларусь" -> station.country.equals("Беларусь", true) &&
                                    (station.name.contains(radioQuery, true) || station.genre.contains(radioQuery, true))
                                "Мир" -> !station.country.equals("Беларусь", true) &&
                                    (station.name.contains(radioQuery, true) || station.genre.contains(radioQuery, true) ||
                                        station.country.contains(radioQuery, true))
                                "Избранное" -> station.id in radioFavorites
                                else -> radioQuery.isBlank() || station.name.contains(radioQuery, true) ||
                                    station.genre.contains(radioQuery, true) || station.country.contains(radioQuery, true)
                            }
                        }
                        if (radioSearchLoading) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(color = Green, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(9.dp))
                                Text("Ищем станции по всему миру…", color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (radioSearchError != null) {
                            Text(radioSearchError!!, color = Color(0xFFFFB49F), fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        if (visibleStations.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Radio, null, tint = Muted, modifier = Modifier.size(42.dp))
                                Text("Станции не найдены", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Попробуй изменить запрос или фильтр", color = Muted, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                                items(visibleStations, key = { it.id }) { station ->
                                    val isCurrent = radioSelected?.id == station.id
                                    val isFavorite = station.id in radioFavorites
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                                            .background(if (isCurrent) Color(0xFF17271D) else Color.Transparent)
                                            .clickable { playRadioStation(station) }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
                                            .background(Color(0xFF20372A)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Radio, null, tint = Green, modifier = Modifier.size(25.dp))
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(station.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(station.country + " · " + station.genre, color = Muted, fontSize = 11.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = {
                                            val updated = if (isFavorite) radioFavorites - station.id else radioFavorites + station.id
                                            radioFavorites = updated
                                            context.getSharedPreferences("tuno_radio", Context.MODE_PRIVATE).edit()
                                                .putStringSet("favorites", updated).apply()
                                        }) {
                                            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                "В избранное", tint = if (isFavorite) Green else Muted)
                                        }
                                        IconButton(onClick = { playRadioStation(station) }) {
                                            Icon(if (isCurrent && playing) Icons.Default.Equalizer else Icons.Default.PlayCircle,
                                                "Слушать", tint = Green, modifier = Modifier.size(27.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Text("Интернет-радио · нужен интернет. Звук идёт через выбранный Android аудиовыход.",
                            color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                    }
                } else {
                    if (activePlaylist == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Твои плейлисты", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(onClick = { pendingCloudForNewPlaylist = null; playlistName = ""; showCreatePlaylist = true }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Создать")
                            }
                        }
                        if (playlists.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.QueueMusic, null, tint = Green, modifier = Modifier.size(54.dp))
                                Text("Плейлистов пока нет", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Создай подборку любимых треков", color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { pendingCloudForNewPlaylist = null; playlistName = ""; showCreatePlaylist = true }) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Новый плейлист")
                                }
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(playlists, key = { it.id }) { playlist ->
                                    Row(Modifier.fillMaxWidth().clickable { activePlaylistId = playlist.id }
                                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(54.dp).background(Color(0xFF20372A), RoundedCornerShape(14.dp)),
                                            contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.QueueMusic, null, tint = Green, modifier = Modifier.size(27.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(playlist.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${playlist.trackIds.size + playlist.soundCloudTracks.size} треков", color = Muted, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = {
                                            playlists = playlists.filterNot { it.id == playlist.id }
                                            savePlaylists(context, playlists)
                                        }) { Icon(Icons.Default.DeleteOutline, null, tint = Muted) }
                                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { activePlaylistId = null }) {
                                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(activePlaylist.name, color = Color.White, fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${activePlaylist.trackIds.size + activePlaylist.soundCloudTracks.size} треков", color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { openAddTracks() }) {
                                Icon(Icons.Default.Add, null, tint = Green)
                            }
                        }
                        val playlistTracks = activePlaylist.trackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
                        val playlistPlaybackQueue = playlistTracks.map { PlaybackQueueItem(local = it) } + activePlaylist.soundCloudTracks.map { PlaybackQueueItem(cloud = it) }
                        if (playlistTracks.isEmpty() && activePlaylist.soundCloudTracks.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.MusicNote, null, tint = Green, modifier = Modifier.size(48.dp))
                                Text("Здесь пока пусто", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Добавь треки из медиатеки", color = Muted, fontSize = 12.sp)
                                Button(onClick = { openAddTracks() }) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Добавить треки")
                                }
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(playlistTracks, key = { it.id }) { track ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        playMixedQueueItem(playlistPlaybackQueue, playlistPlaybackQueue.indexOfFirst { it.local?.id == track.id }.coerceAtLeast(0))
                                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CoverThumb(track.cover, 46)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.customTitle, color = Color.White, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                            Text(track.originalArtist, color = Muted, fontSize = 12.sp, maxLines = 1)
                                        }
                                        IconButton(onClick = {
                                            val updated = activePlaylist.copy(trackIds = activePlaylist.trackIds - track.id)
                                            playlists = playlists.map { if (it.id == updated.id) updated else it }
                                            savePlaylists(context, playlists)
                                        }) { Icon(Icons.Default.RemoveCircleOutline, null, tint = Muted) }
                                    }
                                }
                                items(activePlaylist.soundCloudTracks, key = { "playlist-cloud-" + it.url }) { cloud ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        playMixedQueueItem(playlistPlaybackQueue, playlistPlaybackQueue.indexOfFirst { it.cloud?.url == cloud.url })
                                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (cloud.artwork != null) AsyncImage(model = cloud.artwork, contentDescription = null, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))) else CoverThumb(null, 46)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(cloud.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("SoundCloud · ${cloud.artist}", color = Muted, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = {
                                            val updated = activePlaylist.copy(soundCloudTracks = activePlaylist.soundCloudTracks.filterNot { it.url == cloud.url })
                                            playlists = playlists.map { if (it.id == updated.id) updated else it }
                                            savePlaylists(context, playlists)
                                        }) { Icon(Icons.Default.RemoveCircleOutline, null, tint = Muted) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (soundCloudTrack != null && soundCloudEmbed) {
                CloudMiniPlayer(soundCloudTrack!!, soundCloudPlaying, { fullPlayer = true }, {
                    soundCloudWebView?.evaluateJavascript("window.tunoToggle && window.tunoToggle()", null)
                })
            }
            if (selectedTrack != null && tab != 2) {
                MiniPlayer(
                    track = selectedTrack!!, playing = playing,
                    onOpen = { fullPlayer = true },
                    onPlayPause = { controllerProvider()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() } },
                    onNext = { if (playbackQueue.isNotEmpty()) playNextPlaybackQueueItem() else controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
                )
            }
            NavigationBar(containerColor = Color(0xFF101713), tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; activePlaylistId = null },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Дом") })
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Медиатека") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Избранное") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4; activePlaylistId = null },
                    icon = { Icon(Icons.Default.Radio, null) }, label = { Text("Радио") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3; activePlaylistId = null },
                    icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Плейлисты") })
            }
        }
    }
    }

    if (showRadioFilterDialog) {
        AlertDialog(
            onDismissRequest = { showRadioFilterDialog = false },
            title = { Text("Новый фильтр радио") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = radioFilterName, onValueChange = { radioFilterName = it },
                        label = { Text("Название фильтра") }, singleLine = true)
                    Text("Страна", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Все", "Беларусь").forEach { country ->
                            FilterChip(selected = radioFilterCountry == country,
                                onClick = { radioFilterCountry = country }, label = { Text(country) })
                        }
                    }
                    OutlinedTextField(value = radioFilterGenre, onValueChange = { radioFilterGenre = it },
                        label = { Text("Жанр или слово для поиска") }, singleLine = true,
                        placeholder = { Text("Например: рок, поп, jazz") })
                    Text("Фильтр будет искать станции по стране и жанру в каталоге радио.", color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = radioFilterName.trim()
                    if (name.isNotBlank()) {
                        val filter = RadioFilter(name, radioFilterCountry, radioFilterGenre.trim())
                        radioFilters = radioFilters.filterNot { it.name.equals(name, true) } + filter
                        saveRadioFilters(context, radioFilters)
                        selectedRadioFilter = name
                        radioCountry = "Все"
                    }
                    showRadioFilterDialog = false
                }) { Text("Сохранить", color = Green) }
            },
            dismissButton = { TextButton(onClick = { showRadioFilterDialog = false }) { Text("Отмена") } }
        )
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("Новый плейлист") },
            text = {
                OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                    singleLine = true, label = { Text("Название плейлиста") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistName.trim()
                    if (name.isNotEmpty()) {
                        val created = Playlist(UUID.randomUUID().toString(), name, emptyList(), listOfNotNull(pendingCloudForNewPlaylist))
                        pendingCloudForNewPlaylist = null
                        playlists = playlists + created
                        savePlaylists(context, playlists)
                        activePlaylistId = created.id
                        tab = 3
                    }
                    showCreatePlaylist = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { pendingCloudForNewPlaylist = null; showCreatePlaylist = false }) { Text("Отмена") } }
        )
    }

    if (showAddTracks && activePlaylist != null) {
        AlertDialog(
            onDismissRequest = { showAddTracks = false },
            title = { Text("Добавить треки") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        val alreadyAdded = track.id in activePlaylist.trackIds
                        val checked = track.id in pendingTrackIds
                        Row(Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) {
                            pendingTrackIds = if (checked) pendingTrackIds - track.id else pendingTrackIds + track.id
                        }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alreadyAdded || checked, enabled = !alreadyAdded,
                                onCheckedChange = { value ->
                                    pendingTrackIds = if (value) pendingTrackIds + track.id else pendingTrackIds - track.id
                                })
                            Column(Modifier.weight(1f)) {
                                Text(track.customTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.originalArtist, color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                    item { Text("SOUNDCLOUD", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    items((cloudFavorites + soundCloudResults + listOfNotNull(soundCloudTrack)).distinctBy { it.url }, key = { "pick-cloud-" + it.url }) { cloud ->
                        val alreadyAdded = activePlaylist.soundCloudTracks.any { it.url == cloud.url }
                        val checked = cloud.url in pendingCloudUrls
                        Row(Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) { pendingCloudUrls = if (checked) pendingCloudUrls - cloud.url else pendingCloudUrls + cloud.url }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alreadyAdded || checked, enabled = !alreadyAdded, onCheckedChange = { value -> pendingCloudUrls = if (value) pendingCloudUrls + cloud.url else pendingCloudUrls - cloud.url })
                            CoverThumb(cloud.artwork, 38)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) { Text(cloud.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("SoundCloud · " + cloud.artist, color = Muted, fontSize = 11.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cloudToAdd = (cloudFavorites + soundCloudResults + listOfNotNull(soundCloudTrack)).distinctBy { it.url }.filter { it.url in pendingCloudUrls }
                    val updated = activePlaylist.copy(trackIds = (activePlaylist.trackIds + pendingTrackIds).distinct(), soundCloudTracks = (activePlaylist.soundCloudTracks + cloudToAdd).distinctBy { it.url })
                    playlists = playlists.map { if (it.id == updated.id) updated else it }
                    savePlaylists(context, playlists)
                    pendingTrackIds = emptySet()
                    pendingCloudUrls = emptySet()
                    showAddTracks = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddTracks = false }) { Text("Отмена") } }
        )
    }

    if (trackToAddToPlaylist != null) {
        val targetTrack = trackToAddToPlaylist!!
        AlertDialog(
            onDismissRequest = { trackToAddToPlaylist = null },
            title = { Text("Добавить в плейлист") },
            text = {
                if (playlists.isEmpty()) Text("Плейлистов пока нет. Создай новый с этим треком.")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { "local-picker-" + it.id }) { playlist ->
                        val alreadyAdded = targetTrack.id in playlist.trackIds
                        Row(Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) {
                            val updated = playlist.copy(trackIds = (playlist.trackIds + targetTrack.id).distinct())
                            playlists = playlists.map { if (it.id == updated.id) updated else it }
                            savePlaylists(context, playlists)
                            trackToAddToPlaylist = null
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QueueMusic, null, tint = Green)
                            Spacer(Modifier.width(12.dp))
                            Text(playlist.name, color = Color.White, modifier = Modifier.weight(1f))
                            if (alreadyAdded) Text("Добавлен", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val created = Playlist(UUID.randomUUID().toString(), targetTrack.customTitle, listOf(targetTrack.id))
                    playlists = playlists + created
                    savePlaylists(context, playlists)
                    activePlaylistId = created.id
                    tab = 3
                    trackToAddToPlaylist = null
                }) { Text("Создать плейлист с треком") }
            },
            dismissButton = { TextButton(onClick = { trackToAddToPlaylist = null }) { Text("Закрыть") } }
        )
    }

    if (showCloudPlaylistPicker && soundCloudTrack != null) {
        AlertDialog(
            onDismissRequest = { showCloudPlaylistPicker = false },
            title = { Text("Добавить в плейлист") },
            text = {
                if (playlists.isEmpty()) Text("Плейлистов пока нет. Создай новый, чтобы сохранить этот трек.")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { "cloud-picker-" + it.id }) { playlist ->
                        val cloud = soundCloudTrack!!
                        val alreadyAdded = playlist.soundCloudTracks.any { it.url == cloud.url }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) {
                            val updated = playlist.copy(soundCloudTracks = (playlist.soundCloudTracks + cloud).distinctBy { it.url })
                            playlists = playlists.map { if (it.id == updated.id) updated else it }
                            savePlaylists(context, playlists)
                            showCloudPlaylistPicker = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QueueMusic, null, tint = Green)
                            Spacer(Modifier.width(12.dp))
                            Text(playlist.name, color = Color.White, modifier = Modifier.weight(1f))
                            if (alreadyAdded) Text("Добавлен", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cloud = soundCloudTrack
                    if (cloud != null) {
                        val created = Playlist(UUID.randomUUID().toString(), "SoundCloud", emptyList(), listOf(cloud))
                        playlists = playlists + created
                        savePlaylists(context, playlists)
                        activePlaylistId = created.id
                        tab = 3
                    }
                    showCloudPlaylistPicker = false
                }) { Text("Создать плейлист с треком") }
            },
            dismissButton = { TextButton(onClick = { showCloudPlaylistPicker = false }) { Text("Закрыть") } }
        )
    }

    if (trackToDelete != null) {
        val target = trackToDelete!!
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Удалить трек из Tuno?") },
            text = { Text(if (target.uri.scheme == "file") "«${target.customTitle}» и импортированная копия будут удалены из офлайн-хранилища Tuno." else "«${target.customTitle}» исчезнет из медиатеки и плейлистов Tuno. Сам аудиофайл останется на телефоне.") },
            confirmButton = {
                TextButton(onClick = {
                    val prefs = context.getSharedPreferences("track_edits", Context.MODE_PRIVATE)
                    if (target.uri.scheme == "file") {
                        runCatching { File(target.uri.path ?: "").delete() }
                    } else {
                        val hidden = (prefs.getStringSet("hidden_tracks", emptySet()) ?: emptySet()).toMutableSet()
                        hidden.add(target.id.toString())
                        prefs.edit().putStringSet("hidden_tracks", hidden).apply()
                    }
                    playlists = playlists.map { it.copy(trackIds = it.trackIds - target.id) }
                    savePlaylists(context, playlists)
                    controllerProvider()?.let { player ->
                        if (player.currentMediaItem?.mediaId == target.id.toString()) {
                            player.stop()
                            player.clearMediaItems()
                            selectedTrack = null
                        }
                    }
                    tracks = loadTracks()
                    trackToDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { trackToDelete = null }) { Text("Отмена") } }
        )
    }

    if (editingTrack != null) {
        AlertDialog(
            onDismissRequest = { editingTrack = null },
            title = { Text("Переименовать трек") },
            text = { OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, singleLine = true, label = { Text("Название") }) },
            confirmButton = {
                TextButton(onClick = {
                    val target = editingTrack!!
                    context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).edit()
                        .putString("title_${target.id}", editTitle.ifBlank { target.originalTitle }).apply()
                    tracks = loadTracks()
                    selectedTrack = tracks.firstOrNull { it.id == target.id }
                    editingTrack = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingTrack = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun TunoVisualizer(isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "tuno_visualizer")
    Row(
        Modifier.width(92.dp).height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(12) { index ->
            val animatedHeight by transition.animateFloat(
                initialValue = if (isPlaying) 0.18f + (index % 3) * 0.06f else 0.16f,
                targetValue = if (isPlaying) 0.35f + ((index * 7) % 10) * 0.065f else 0.16f,
                animationSpec = infiniteRepeatable(
                    animation = tween(320 + index * 38, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "visualizer_bar_$index"
            )
            Box(
                Modifier.weight(1f)
                    .height((5f + animatedHeight * 27f).dp)
                    .clip(CircleShape)
                    .background(Green.copy(alpha = if (isPlaying) 0.95f else 0.48f))
            )
        }
    }
}

@Composable
private fun AudiusPlayerCard(
    track: AudiusTrack,
    playing: Boolean,
    position: Float,
    duration: Float,
    downloading: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onDownload: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF244832), Color(0xFF142019))))
            .border(1.dp, Green.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Text("СЕЙЧАС ИГРАЕТ · AUDIUS", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(track.artwork, 76)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp).background(Green, CircleShape)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Night, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = if (duration > 0f) position.coerceIn(0f, duration) else 0f,
            onValueChange = onSeek,
            valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Color.White.copy(alpha = 0.22f))
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, fontSize = 11.sp)
            Text(formatTime(duration), color = Muted, fontSize = 11.sp)
        }
        if (track.isDownloadable) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDownload, enabled = !downloading, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.6f))) {
                if (downloading) CircularProgressIndicator(Modifier.size(17.dp), color = Green, strokeWidth = 2.dp)
                else Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (downloading) "Скачивание…" else "Скачать")
            }
        } else {
            Text("Скачивание недоступно: автор не разрешил загрузку", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun CloudMiniPlayer(track: SoundCloudResult, playing: Boolean, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Color(0xFF17251D), RoundedCornerShape(17.dp)).clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverThumb(track.artwork, 44)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("SoundCloud · " + track.artist, color = Muted, maxLines = 1, fontSize = 11.sp)
        }
        IconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Green) }
    }
}

@Composable
private fun MiniPlayer(track: AudioTrack, playing: Boolean, onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Color(0xFF17251D), RoundedCornerShape(17.dp)).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(track.cover, 44)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.customTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(track.originalArtist, color = Muted, maxLines = 1, fontSize = 11.sp)
        }
        IconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Green) }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
    }
}

@Composable
private fun FullPlayerScreen(
    track: AudioTrack?, playing: Boolean, position: Float, duration: Float,
    cloudTrack: SoundCloudResult? = null, cloudFavorite: Boolean = false,
    onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onCover: () -> Unit, onRename: () -> Unit,
    onFavorite: () -> Unit, onAddToPlaylist: () -> Unit = {},
    audiusTrack: AudiusTrack? = null, onDownload: () -> Unit = {}
) {
    val displayTitle = cloudTrack?.title ?: track?.customTitle ?: "Выбери трек"
    val displayArtist = cloudTrack?.artist ?: track?.originalArtist ?: "Неизвестный исполнитель"
    val displayCover = cloudTrack?.artwork ?: track?.cover
    val displayFavorite = if (cloudTrack != null) cloudFavorite else track?.isFavorite == true
    var seekPosition by remember(cloudTrack?.url, track?.id) { mutableFloatStateOf(position) }
    var isSeeking by remember { mutableStateOf(false) }
    LaunchedEffect(position, isSeeking) {
        if (!isSeeking) seekPosition = position
    }
    val motion = rememberInfiniteTransition(label = "player_motion")
    val albumRotation by motion.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(18000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "album_rotation"
    )
    val pulse by motion.animateFloat(
        initialValue = 1f, targetValue = 1.045f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "play_button_pulse"
    )
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFF1B3526), Night, Night)))
        .padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.weight(1f))
            Text("СЕЙЧАС ИГРАЕТ", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            if (cloudTrack == null) IconButton(onClick = onRename) { Icon(Icons.Default.MoreHoriz, null, tint = Color.White) }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().weight(0.9f).clickable(enabled = cloudTrack == null, onClick = onCover), contentAlignment = Alignment.Center) {
            VinylRecord(
                artwork = displayCover,
                rotation = if (playing) albumRotation else 0f
            )
            if (cloudTrack == null) {
                Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color.Black.copy(alpha = 0.25f), shape = CircleShape) {
                    Icon(Icons.Default.Edit, "Изменить обложку", tint = Color.White, modifier = Modifier.padding(10.dp).size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(displayTitle, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayArtist, color = Color.White.copy(alpha = 0.72f), modifier = Modifier.weight(1f), maxLines = 1)
            if (cloudTrack == null) Text("Обложка", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.clickable(onClick = onCover).padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Slider(value = if (duration > 0) seekPosition.coerceIn(0f, duration) else 0f,
            onValueChange = { seekPosition = it; isSeeking = true },
            onValueChangeFinished = { onSeek(seekPosition); isSeeking = false },
            valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (isSeeking) seekPosition else position), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFavorite) { Icon(if (displayFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (displayFavorite) Green else Color.White, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp).graphicsLayer { scaleX = if (playing) pulse else 1f; scaleY = if (playing) pulse else 1f }.background(Color.White, CircleShape)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color(0xFF14291D), modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
            if (cloudTrack != null) IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Default.PlaylistAdd, "Добавить в плейлист", tint = Green, modifier = Modifier.size(28.dp))
            } else if (audiusTrack == null) IconButton(onClick = onRename) { Icon(Icons.Default.MoreVert, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        if (audiusTrack?.isDownloadable == true) {
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
                border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.65f))) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Скачать для офлайн-прослушивания")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun VinylRecord(artwork: String?, rotation: Float) {
    Box(
        Modifier.fillMaxWidth(0.88f).aspectRatio(1f)
            .graphicsLayer { rotationZ = rotation }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF343A36), Color(0xFF171B19), Color(0xFF050706))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Fine concentric grooves make the artwork read as a vinyl record.
        repeat(7) { index ->
            Box(
                Modifier.fillMaxSize((0.96f - index * 0.085f).coerceAtLeast(0.40f))
                    .border(1.dp, Color.White.copy(alpha = 0.075f), CircleShape)
            )
        }
        Box(
            Modifier.fillMaxSize(0.40f)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFB85F60), Color(0xFF9A6B9B))))
                .border(2.dp, Color(0xFF0A0D0B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = "Обложка трека на виниловой пластинке",
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.52f))
            }
        }
        Box(Modifier.fillMaxSize(0.035f).background(Color(0xFF080B0A), CircleShape))
    }
}

@Composable
private fun CoverThumb(uri: String?, size: Int) {
    if (uri != null) AsyncImage(model = uri, contentDescription = "Обложка трека",
        modifier = Modifier.size(size.dp).background(Panel, RoundedCornerShape(12.dp)))
    else Box(Modifier.size(size.dp).background(Panel, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Green)
    }
}

private fun toMediaItem(track: AudioTrack) = MediaItem.Builder().setUri(track.uri).setMediaId(track.id.toString()).setMediaMetadata(
    MediaMetadata.Builder().setTitle(track.customTitle).setArtist(track.originalArtist)
        .setArtworkUri(track.cover?.let { Uri.parse(it) }).build()
).build()

private fun formatTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
