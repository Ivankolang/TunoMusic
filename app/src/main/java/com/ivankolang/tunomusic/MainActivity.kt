package com.ivankolang.tunomusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
                add(Playlist(item.optString("id"), item.optString("name"), trackIds))
            }
        }
    }.getOrDefault(emptyList())
}

private fun savePlaylists(context: Context, playlists: List<Playlist>) {
    val array = JSONArray()
    playlists.forEach { playlist ->
        val ids = JSONArray()
        playlist.trackIds.forEach { trackId -> ids.put(trackId) }
        array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("trackIds", ids))
    }
    context.getSharedPreferences("tuno_playlists", Context.MODE_PRIVATE).edit()
        .putString("items", array.toString()).apply()
}

data class Playlist(val id: String, val name: String, val trackIds: List<Long>)

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
    var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var showAddTracks by remember { mutableStateOf(false) }
    var pendingTrackIds by remember { mutableStateOf(emptySet<Long>()) }
    val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }
    var serviceUrl by rememberSaveable { mutableStateOf("") }
    var soundCloudEmbed by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var coverTarget by remember { mutableStateOf<AudioTrack?>(null) }
    val controller = controllerProvider()
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
            track = selectedTrack, playing = playing, position = position, duration = duration,
            onBack = { fullPlayer = false },
            onPlayPause = { controllerProvider()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() } },
            onNext = { controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
            onPrevious = { controllerProvider()?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } },
            onSeek = { controllerProvider()?.seekTo((it * 1000).toLong()) },
            onCover = { selectedTrack?.let { coverTarget = it; coverPicker.launch(arrayOf("image/*")) } },
            onRename = { selectedTrack?.let { editingTrack = it; editTitle = it.customTitle } },
            onFavorite = { selectedTrack?.let { track ->
                val value = !track.isFavorite
                context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).edit().putBoolean("favorite_${track.id}", value).apply()
                tracks = loadTracks()
                selectedTrack = tracks.firstOrNull { it.id == track.id }
            } }
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
                    if (tracks.isEmpty()) {
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
                                    selectedTrack = track
                                    controllerProvider()?.let { c ->
                                        c.setMediaItems(tracks.map { toMediaItem(it) }, tracks.indexOf(track), 0L)
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
                        if (soundCloudEmbed) {
                            IconButton(onClick = {
                                soundCloudEmbed = false
                                serviceUrl = "https://soundcloud.com/search/sounds?q=" + URLEncoder.encode(query.trim(), "UTF-8")
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
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("Трек, исполнитель или альбом", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        trailingIcon = { IconButton(onClick = {
                            val q = URLEncoder.encode(query.trim(), "UTF-8")
                            if (q.isNotBlank()) serviceUrl = "https://soundcloud.com/search/sounds?q=$q"
                        }) { Icon(Icons.Default.ArrowForward, null, tint = Green) } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedContainerColor = Color(0xFF111915), unfocusedContainerColor = Color(0xFF111915),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    if (serviceUrl.isBlank()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.TravelExplore, null, tint = Green, modifier = Modifier.size(54.dp))
                            Text("Ищи музыку в выбранном сервисе", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Результаты откроются здесь, внутри Tuno", color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Night)
                                .border(1.dp, Color(0xFF26352C), RoundedCornerShape(20.dp)),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    tag = serviceUrl
                                    setBackgroundColor(android.graphics.Color.rgb(8, 11, 10))
                                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                                    isVerticalScrollBarEnabled = false
                                    isHorizontalScrollBarEnabled = false
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.loadsImagesAutomatically = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                            val uri = request.url
                                            val host = uri.host?.lowercase().orEmpty()
                                            val path = uri.path.orEmpty()
                                            val reserved = listOf("/search/", "/discover", "/you", "/stream", "/upload",
                                                "/signin", "/signup", "/settings", "/pages", "/charts", "/popular", "/stations")
                                            val isSoundPage = (host == "soundcloud.com" || host == "www.soundcloud.com") &&
                                                path.count { it == '/' } >= 2 &&
                                                reserved.none { path.startsWith(it) }
                                            if (request.isForMainFrame && isSoundPage) {
                                                val trackUrl = uri.toString()
                                                val encoded = URLEncoder.encode(trackUrl, "UTF-8")
                                                soundCloudEmbed = true
                                                view.loadUrl("https://w.soundcloud.com/player/?url=$encoded&color=%239BE7B1&auto_play=true&hide_related=true&show_comments=false&show_user=true&show_reposts=false&visual=true")
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            super.onPageFinished(view, url)
                                            val css = """
                                                :root {
                                                  color-scheme: dark !important;
                                                  --sc-orange: #9BE7B1 !important;
                                                  --sc-primary: #9BE7B1 !important;
                                                }
                                                html, body, #app, #app > div, .l-container, .l-content,
                                                .discover, .search, .searchList, .soundList,
                                                .playControls, .listenPage, .listenPage__content {
                                                  background: #080B0A !important;
                                                  color: #F2F7F3 !important;
                                                }
                                                body, button, input, textarea {
                                                  font-family: Roboto, Arial, sans-serif !important;
                                                }
                                                a, .sc-link, .sc-link-light {
                                                  color: #9BE7B1 !important;
                                                }
                                                button, [role=button], .sc-button {
                                                  border-radius: 12px !important;
                                                }
                                                input, textarea, .textfield {
                                                  background: #151B18 !important;
                                                  color: #F2F7F3 !important;
                                                  border: 1px solid #344139 !important;
                                                }
                                                .sound, .sound__body, .sound__content, .soundList__item,
                                                .searchList__item, .listenEngagement, .userInfo,
                                                .sound__footer, .sound__header, .playlist,
                                                .playlist__item, .userBadge {
                                                  background-color: #111915 !important;
                                                  color: #F2F7F3 !important;
                                                  border-color: #26352C !important;
                                                }
                                                .soundTitle__title, .soundTitle__username, .sound__title,
                                                .sound__username, .sc-text, .sc-type-light,
                                                h1, h2, h3, p, span, label {
                                                  color: #E8F1EA !important;
                                                }
                                                .sc-button-cta, .sc-button-cta.sc-button,
                                                .sc-button-primary, .playButton,
                                                .playButton g, .sc-button-play {
                                                  background-color: #9BE7B1 !important;
                                                  color: #08110B !important;
                                                  fill: #08110B !important;
                                                }
                                                .header, .l-header, .header__inner,
                                                .header__navMenu, .header__userNav {
                                                  background: #080B0A !important;
                                                  border-color: #26352C !important;
                                                }
                                                ::-webkit-scrollbar { width: 4px; height: 4px; }
                                                ::-webkit-scrollbar-thumb {
                                                  background: #42634D; border-radius: 8px;
                                                }
                                            """.trimIndent()
                                            val escaped = css.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'")
                                            val script = """
                                                (function() {
                                                  var id = 'tuno-soundcloud-theme';
                                                  var cssText = '$escaped';
                                                  function applyTunoTheme() {
                                                    if (!document.head) return;
                                                    var style = document.getElementById(id);
                                                    if (!style) {
                                                      style = document.createElement('style');
                                                      style.id = id;
                                                      document.head.appendChild(style);
                                                    }
                                                    if (style.textContent !== cssText) style.textContent = cssText;
                                                    document.documentElement.style.background = '#080B0A';
                                                    if (document.body) document.body.style.background = '#080B0A';
                                                  }
                                                  applyTunoTheme();
                                                  if (!window.__tunoThemeObserver) {
                                                    window.__tunoThemeObserver = new MutationObserver(function() {
                                                      if (!document.getElementById(id)) applyTunoTheme();
                                                    });
                                                    window.__tunoThemeObserver.observe(document.documentElement, {
                                                      childList: true, subtree: true
                                                    });
                                                  }
                                                })();
                                            """.trimIndent()
                                            view.evaluateJavascript(script, null)
                                        }
                                    }
                                    loadUrl(serviceUrl)
                                }
                            },
                            update = { web ->
                                if (web.tag != serviceUrl) {
                                    web.tag = serviceUrl
                                    web.loadUrl(serviceUrl)
                                }
                            }
                        )
                    }
                } else {
                    if (activePlaylist == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Твои плейлисты", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(onClick = { playlistName = ""; showCreatePlaylist = true }) {
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
                                Button(onClick = { playlistName = ""; showCreatePlaylist = true }) {
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
                                            Text("${playlist.trackIds.size} треков", color = Muted, fontSize = 12.sp)
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
                                Text("${activePlaylist.trackIds.size} треков", color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { pendingTrackIds = emptySet(); showAddTracks = true }) {
                                Icon(Icons.Default.Add, null, tint = Green)
                            }
                        }
                        val playlistTracks = tracks.filter { it.id in activePlaylist.trackIds }
                        if (playlistTracks.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.MusicNote, null, tint = Green, modifier = Modifier.size(48.dp))
                                Text("Здесь пока пусто", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Добавь треки из медиатеки", color = Muted, fontSize = 12.sp)
                                Button(onClick = { pendingTrackIds = emptySet(); showAddTracks = true }) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Добавить треки")
                                }
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                items(playlistTracks, key = { it.id }) { track ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        selectedTrack = track
                                        controllerProvider()?.let { c ->
                                            c.setMediaItems(playlistTracks.map { toMediaItem(it) }, playlistTracks.indexOf(track), 0L)
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
                            }
                        }
                    }
                }
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
                        val created = Playlist(UUID.randomUUID().toString(), name, emptyList())
                        playlists = playlists + created
                        savePlaylists(context, playlists)
                        activePlaylistId = created.id
                        tab = 3
                    }
                    showCreatePlaylist = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Отмена") } }
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = activePlaylist.copy(trackIds = (activePlaylist.trackIds + pendingTrackIds).distinct())
                    playlists = playlists.map { if (it.id == updated.id) updated else it }
                    savePlaylists(context, playlists)
                    pendingTrackIds = emptySet()
                    showAddTracks = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddTracks = false }) { Text("Отмена") } }
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
    onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onCover: () -> Unit, onRename: () -> Unit,
    onFavorite: () -> Unit
) {
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
            IconButton(onClick = onRename) { Icon(Icons.Default.MoreHoriz, null, tint = Color.White) }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().weight(0.9f).clickable(onClick = onCover), contentAlignment = Alignment.Center) {
            if (track?.cover != null) {
                AsyncImage(model = track.cover, contentDescription = "Обложка", modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (playing) albumRotation else 0f })
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFB85F60), Color(0xFFB66A4C), Color(0xFF9A6B9B))),
                    RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.24f), modifier = Modifier.size(150.dp).graphicsLayer { rotationZ = if (playing) albumRotation else 0f })
                }
            }
            Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color.Black.copy(alpha = 0.25f), shape = CircleShape) {
                Icon(Icons.Default.Edit, "Изменить обложку", tint = Color.White, modifier = Modifier.padding(10.dp).size(18.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(track?.customTitle ?: "Выбери трек", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track?.originalArtist ?: "Неизвестный исполнитель", color = Color.White.copy(alpha = 0.72f), modifier = Modifier.weight(1f), maxLines = 1)
            Text("Обложка", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.clickable(onClick = onCover).padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Slider(value = if (duration > 0) position.coerceIn(0f, duration) else 0f,
            onValueChange = { onSeek(it) }, valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFavorite) { Icon(if (track?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (track?.isFavorite == true) Green else Color.White, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp).graphicsLayer { scaleX = if (playing) pulse else 1f; scaleY = if (playing) pulse else 1f }.background(Color.White, CircleShape)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color(0xFF14291D), modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
            IconButton(onClick = onRename) { Icon(Icons.Default.MoreVert, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
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
