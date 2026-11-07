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
import android.provider.MediaStore
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

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
                            t.optString("artwork").takeIf { it.startsWith("http") }))
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
            cloud.put(JSONObject().put("title", t.title).put("artist", t.artist).put("url", t.url).put("artwork", t.artwork ?: ""))
        }
        array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("trackIds", ids).put("soundCloudTracks", cloud))
    }
    context.getSharedPreferences("tuno_playlists", Context.MODE_PRIVATE).edit()
        .putString("items", array.toString()).apply()
}

data class Playlist(val id: String, val name: String, val trackIds: List<Long>, val soundCloudTracks: List<SoundCloudResult> = emptyList())
data class SoundCloudResult(val title: String, val artist: String, val url: String, val artwork: String?)
data class CatalogRecording(
    val id: String,
    val title: String,
    val artist: String,
    val releaseId: String?,
    val artwork: String? = null
)

private suspend fun searchMusicCatalog(query: String): List<CatalogRecording> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val connection = (URL("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=25")
        .openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 12000
        setRequestProperty("User-Agent", "TunoMusic/0.2 (https://github.com/Ivankolang/TunoMusic)")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val recordings = JSONObject(body).optJSONArray("recordings") ?: JSONArray()
        buildList {
            for (i in 0 until recordings.length()) {
                val item = recordings.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val credits = item.optJSONArray("artist-credit") ?: JSONArray()
                val artists = buildList {
                    for (j in 0 until credits.length()) {
                        val credit = credits.optJSONObject(j) ?: continue
                        val artist = credit.optJSONObject("artist")?.optString("name").orEmpty()
                        if (artist.isNotBlank()) add(artist)
                    }
                }.joinToString("")
                val releases = item.optJSONArray("releases") ?: JSONArray()
                val releaseId = releases.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                add(CatalogRecording(item.optString("id"), title, artists.ifBlank { "Неизвестный исполнитель" }, releaseId))
            }
        }
    } finally {
        connection.disconnect()
    }
}

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
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val prefs = getSharedPreferences("track_edits", Context.MODE_PRIVATE)
        val result = mutableListOf<AudioTrack>()
        val hiddenIds = getSharedPreferences("track_edits", Context.MODE_PRIVATE)
            .getStringSet("hidden_tracks", emptySet()) ?: emptySet()
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var fullPlayer by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var trackToDelete by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var service by remember { mutableStateOf("SoundCloud") }
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
    var catalogResults by remember { mutableStateOf(emptyList<CatalogRecording>()) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf(false) }
    var catalogMode by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var soundCloudLoadFailed by remember { mutableStateOf(false) }
    var soundCloudWebView by remember { mutableStateOf<WebView?>(null) }
    var soundCloudPlaying by remember { mutableStateOf(false) }
    var soundCloudTrack by remember { mutableStateOf<SoundCloudResult?>(null) }
    var soundCloudPosition by remember { mutableLongStateOf(0L) }
    var soundCloudDuration by remember { mutableLongStateOf(0L) }
    var soundCloudQueue by remember { mutableStateOf(emptyList<SoundCloudResult>()) }
    var soundCloudQueueIndex by remember { mutableIntStateOf(0) }
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

    fun stopSoundCloudPlayback() {
        soundCloudWebView?.evaluateJavascript("window.tunoPause && window.tunoPause()", null)
        soundCloudPlaying = false
        runCatching {
            context.startService(Intent(context, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_CLOUD_STOP))
        }
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
        tab = 2
        val encoded = URLEncoder.encode(cloud.url, "UTF-8")
        serviceUrl = "https://w.soundcloud.com/player/?url=$encoded&color=%239BE7B1&auto_play=true&hide_related=true&show_comments=false&show_user=true&show_reposts=false&visual=true"
    }

    fun playNextCloudTrack() {
        if (soundCloudQueueIndex + 1 < soundCloudQueue.size) playCloudQueueItem(soundCloudQueue, soundCloudQueueIndex + 1)
    }

    fun playPreviousCloudTrack() {
        if (soundCloudQueueIndex > 0) playCloudQueueItem(soundCloudQueue, soundCloudQueueIndex - 1)
    }

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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tracks = loadTracks() }
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

    BackHandler(enabled = fullPlayer || activePlaylistId != null || (tab == 2 && soundCloudEmbed)) {
        when {
            fullPlayer -> fullPlayer = false
            tab == 2 && soundCloudEmbed -> {
                soundCloudEmbed = false
                serviceUrl = "https://soundcloud.com/search/sounds?q=" + URLEncoder.encode(query.trim(), "UTF-8")
            }
            else -> activePlaylistId = null
        }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) tracks = loadTracks()
    }
    LaunchedEffect(serviceUrl, soundCloudResults) {
        if (serviceUrl.isNotBlank() && !soundCloudEmbed && soundCloudResults.isEmpty()) {
            soundCloudLoadFailed = false
            delay(35000)
            if (soundCloudResults.isEmpty()) soundCloudLoadFailed = true
        }
    }
    DisposableEffect(controller, tracks) {
        val player = controller
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                if (id != null) selectedTrack = tracks.firstOrNull { it.id == id }
                else if (mediaItem == null) selectedTrack = null
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
            controller?.let {
                playing = it.isPlaying
                if (it.mediaItemCount > 0) {
                    duration = (it.duration.coerceAtLeast(0L) / 1000f).coerceAtLeast(0f)
                    position = (it.currentPosition.coerceAtLeast(0L) / 1000f).coerceAtLeast(0f)
                }
            }
            delay(500)
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
            playing = if (soundCloudEmbed) soundCloudPlaying else playing,
            position = if (soundCloudEmbed) soundCloudPosition / 1000f else position,
            duration = if (soundCloudEmbed) soundCloudDuration / 1000f else duration,
            onBack = { fullPlayer = false },
            onAddToPlaylist = { showCloudPlaylistPicker = true },
            onPlayPause = {
                if (soundCloudEmbed) soundCloudWebView?.evaluateJavascript("window.tunoToggle && window.tunoToggle()", null)
                else controllerProvider()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
            },
            onNext = {
                if (soundCloudEmbed) playNextCloudTrack()
                else controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            },
            onPrevious = {
                if (soundCloudEmbed) playPreviousCloudTrack()
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
                        Text(when (tab) { 0 -> "Твоя музыка"; 1 -> "Избранное"; 2 -> "Поиск музыки"; else -> "Плейлисты" }, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(34.dp))
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
                    val filtered = tracks.filter { (tab != 1 || it.isFavorite) && (it.customTitle.contains(query, true) || it.originalArtist.contains(query, true)) }
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
                                    stopSoundCloudPlayback()
                                    soundCloudEmbed = false
                                    selectedTrack = track
                                    val playQueue = filtered
                                    controllerProvider()?.let { c ->
                                        c.setMediaItems(playQueue.map { toMediaItem(it) }, playQueue.indexOf(track).coerceAtLeast(0), 0L)
                                        c.prepare(); c.play()
                                    }
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
                                                text = { Text("Удалить из медиатеки") },
                                                onClick = { menuExpanded = false; trackToDelete = track },
                                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                                            )
                                        }
                                    }
                                }
                            }
                            if (tab == 1) {
                                items(cloudFavorites.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, key = { "cloud-fav-" + it.url }) { cloud ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        playCloudQueueItem(cloudFavorites, cloudFavorites.indexOfFirst { it.url == cloud.url })
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
                        if (soundCloudEmbed || !catalogMode) {
                            IconButton(onClick = {
                                soundCloudEmbed = false
                                catalogMode = true
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
                            Text("SOUNDCLOUD", color = Muted, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                            Text(if (soundCloudEmbed) "Плеер Tuno" else if (catalogMode) "Каталог Tuno" else "Источник аудио",
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
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("Трек, исполнитель или альбом", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        trailingIcon = { IconButton(onClick = {
                            val q = URLEncoder.encode(query.trim(), "UTF-8")
                            if (q.isNotBlank()) {
                                catalogMode = true
                                catalogLoading = true
                                catalogError = false
                                catalogResults = emptyList()
                                soundCloudResults = emptyList()
                                soundCloudLoadFailed = false
                                serviceUrl = ""
                                scope.launch {
                                    try {
                                        catalogResults = searchMusicCatalog(query.trim())
                                        catalogError = catalogResults.isEmpty()
                                    } catch (_: Exception) {
                                        catalogError = true
                                    } finally {
                                        catalogLoading = false
                                    }
                                }
                            }
                        }) { Icon(Icons.Default.ArrowForward, null, tint = Green) } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedContainerColor = Color(0xFF111915), unfocusedContainerColor = Color(0xFF111915),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    if (catalogMode) {
                        when {
                            catalogLoading -> Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = Green)
                                Spacer(Modifier.height(12.dp))
                                Text("Ищем в музыкальном каталоге…", color = Muted)
                            }
                            catalogResults.isNotEmpty() -> LazyColumn(
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                                    .background(Night).border(1.dp, Color(0xFF26352C), RoundedCornerShape(18.dp)),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(catalogResults, key = { it.id }) { result ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            val sourceQuery = URLEncoder.encode(
                                                listOf(result.title, result.artist).filter { it.isNotBlank() }.joinToString(" "),
                                                "UTF-8"
                                            )
                                            query = listOf(result.title, result.artist).filter { it.isNotBlank() }.joinToString(" ")
                                            catalogMode = false
                                            soundCloudResults = emptyList()
                                            soundCloudLoadFailed = false
                                            serviceUrl = "https://soundcloud.com/search/sounds?q=$sourceQuery"
                                        }.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Panel),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Default.MusicNote, null, tint = Green) }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(result.title, color = Color.White, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text(result.artist, color = Muted, fontSize = 12.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("MusicBrainz · найти аудио", color = Green, fontSize = 10.sp)
                                        }
                                        Icon(Icons.Default.Search, null, tint = Green)
                                    }
                                }
                            }
                            catalogError -> Column(
                                Modifier.fillMaxSize().padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CloudOff, null, tint = Muted, modifier = Modifier.size(38.dp))
                                Text("Каталог не нашёл результаты", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Можно попробовать поиск аудио напрямую в SoundCloud", color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = {
                                    val q = URLEncoder.encode(query.trim(), "UTF-8")
                                    catalogMode = false
                                    soundCloudResults = emptyList()
                                    soundCloudLoadFailed = false
                                    serviceUrl = "https://soundcloud.com/search/sounds?q=$q"
                                }) { Text("Искать в SoundCloud") }
                            }
                            else -> Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.TravelExplore, null, tint = Green, modifier = Modifier.size(54.dp))
                                Text("Музыкальный каталог Tuno", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Ищи треки по названию или исполнителю", color = Muted, fontSize = 12.sp)
                            }
                        }
                    } else if (serviceUrl.isBlank()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.TravelExplore, null, tint = Green, modifier = Modifier.size(54.dp))
                            Text("Выбери трек из каталога", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Tuno подберёт совпадения в SoundCloud", color = Muted, fontSize = 12.sp)
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
                                    WebView(ctx).apply {
                                        soundCloudWebView = this
                                        tag = serviceUrl
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
                                            onFinished = { context.mainExecutor.execute { playNextCloudTrack() } }
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
                                                                    item.optString("artwork").takeIf { it.startsWith("http") }))
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
                                                          var seen = window.__tunoSeen || (window.__tunoSeen = Object.create(null));
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
                                                            return decodeURIComponent(parts[1]).replace(/[-_]+/g, " ").replace(/\s+/g, " ").trim();
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
                                                                if (parts.length !== 2) continue;
                                                                if (/^(search|discover|you|stream|charts|popular|stations|sets|tags|pages|upload|signin|signup)$/i.test(parts[0])) continue;
                                                                var clean = "https://soundcloud.com/" + parts[0] + "/" + parts[1];
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
                                                                  artwork:img ? (img.currentSrc || img.src || "") : ""});
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
                                        if (soundCloudEmbed) {
                                            loadDataWithBaseURL("https://w.soundcloud.com/", soundCloudWidgetHtml(serviceUrl),
                                                "text/html", "UTF-8", null)
                                        } else {
                                            loadUrl(serviceUrl)
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
                                                serviceUrl = "https://soundcloud.com/search/sounds?q=$q&retry=" + System.currentTimeMillis()
                                            }) { Text("Повторить поиск") }
                                        } else {
                                            CircularProgressIndicator(color = Green, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.height(12.dp))
                                            Text("Ищем треки…", color = Muted, fontSize = 13.sp)
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
                                                    val encoded = URLEncoder.encode(result.url, "UTF-8")
                                                    soundCloudTrack = result
                                                    soundCloudPosition = 0L
                                                    soundCloudDuration = 0L
                                                    soundCloudEmbed = true
                                                    serviceUrl = "https://w.soundcloud.com/player/?url=$encoded&color=%239BE7B1&auto_play=true&hide_related=true&show_comments=false&show_user=true&show_reposts=false&visual=true"
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
                                                    Text(result.artist.ifBlank { "SoundCloud" }, color = Muted, fontSize = 12.sp,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Icon(Icons.Default.PlayCircle, null, tint = Green, modifier = Modifier.size(28.dp))
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
                            IconButton(onClick = { pendingTrackIds = emptySet(); pendingCloudUrls = emptySet(); showAddTracks = true }) {
                                Icon(Icons.Default.Add, null, tint = Green)
                            }
                        }
                        val playlistTracks = activePlaylist.trackIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
                        if (playlistTracks.isEmpty() && activePlaylist.soundCloudTracks.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.MusicNote, null, tint = Green, modifier = Modifier.size(48.dp))
                                Text("Здесь пока пусто", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Добавь треки из медиатеки", color = Muted, fontSize = 12.sp)
                                Button(onClick = { pendingTrackIds = emptySet(); pendingCloudUrls = emptySet(); showAddTracks = true }) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Добавить треки")
                                }
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(playlistTracks, key = { it.id }) { track ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        stopSoundCloudPlayback()
                                        soundCloudEmbed = false
                                        selectedTrack = track
                                        controllerProvider()?.let { c ->
                                            c.setMediaItems(playlistTracks.map { toMediaItem(it) }, playlistTracks.indexOf(track).coerceAtLeast(0), 0L)
                                            c.prepare(); c.play()
                                        }
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
                                        playCloudQueueItem(activePlaylist.soundCloudTracks, activePlaylist.soundCloudTracks.indexOfFirst { it.url == cloud.url })
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

            if (soundCloudTrack != null && tab == 2 && soundCloudEmbed) {
                CloudMiniPlayer(soundCloudTrack!!, soundCloudPlaying, { fullPlayer = true }, {
                    soundCloudWebView?.evaluateJavascript("window.tunoToggle && window.tunoToggle()", null)
                })
            }
            if (selectedTrack != null && tab != 2) {
                MiniPlayer(
                    track = selectedTrack!!, playing = playing,
                    onOpen = { fullPlayer = true },
                    onPlayPause = { controllerProvider()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() } },
                    onNext = { controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
                )
            }
            NavigationBar(containerColor = Color(0xFF101713), tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Медиатека") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Избранное") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; activePlaylistId = null },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Поиск") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3; activePlaylistId = null },
                    icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Плейлисты") })
            }
        }
    }
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
            text = { Text("«${target.customTitle}» исчезнет из медиатеки и плейлистов Tuno. Сам аудиофайл останется на телефоне.") },
            confirmButton = {
                TextButton(onClick = {
                    val prefs = context.getSharedPreferences("track_edits", Context.MODE_PRIVATE)
                    val hidden = (prefs.getStringSet("hidden_tracks", emptySet()) ?: emptySet()).toMutableSet()
                    hidden.add(target.id.toString())
                    prefs.edit().putStringSet("hidden_tracks", hidden).apply()
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
    onFavorite: () -> Unit, onAddToPlaylist: () -> Unit = {}
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
            if (cloudTrack != null) IconButton(onClick = onAddToPlaylist) { Icon(Icons.Default.PlaylistAdd, "Добавить в плейлист", tint = Green) }
            else IconButton(onClick = onRename) { Icon(Icons.Default.MoreHoriz, null, tint = Color.White) }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().weight(0.9f).clickable(enabled = cloudTrack == null, onClick = onCover), contentAlignment = Alignment.Center) {
            if (displayCover != null) {
                AsyncImage(model = displayCover, contentDescription = "Обложка", modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (playing) albumRotation else 0f })
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFB85F60), Color(0xFFB66A4C), Color(0xFF9A6B9B))),
                    RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.24f), modifier = Modifier.size(150.dp).graphicsLayer { rotationZ = if (playing) albumRotation else 0f })
                }
            }
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
            if (cloudTrack == null) IconButton(onClick = onRename) { Icon(Icons.Default.MoreVert, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(26.dp))
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
