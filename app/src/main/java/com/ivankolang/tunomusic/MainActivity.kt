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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.net.URLEncoder

private val Night = Color(0xFF080B0A)
private val Panel = Color(0xFF151B18)
private val Green = Color(0xFF9BE7B1)
private val Muted = Color(0xFF9AA69E)

data class AudioTrack(
    val id: Long,
    val originalTitle: String,
    val originalArtist: String,
    val uri: Uri,
    val customTitle: String = originalTitle,
    val cover: String? = null
)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { mediaController = controllerFuture?.get() }
        }, ContextCompat.getMainExecutor(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Night, surface = Panel, primary = Green,
                onBackground = Color.White, onSurface = Color.White
            )) {
                TunoScreen(
                    context = this,
                    controllerProvider = { mediaController },
                    loadTracks = { loadAudioTracks() }
                )
            }
        }
    }

    private fun loadAudioTracks(): List<AudioTrack> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                         else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val prefs = getSharedPreferences("track_edits", Context.MODE_PRIVATE)
        val result = mutableListOf<AudioTrack>()
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
                val uri = Uri.withAppendedPath(collection, id.toString())
                result += AudioTrack(
                    id, title, artist, uri,
                    prefs.getString("title_$id", title) ?: title,
                    prefs.getString("cover_$id", null)
                )
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
private fun TunoScreen(
    context: Context,
    controllerProvider: () -> MediaController?,
    loadTracks: () -> List<AudioTrack>
) {
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var serviceQuery by remember { mutableStateOf("") }
    var service by remember { mutableStateOf("SoundCloud") }
    var serviceUrl by remember { mutableStateOf("https://soundcloud.com") }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    val controller = controllerProvider()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                     else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tracks = loadTracks()
    }
    var coverTarget by remember { mutableStateOf<AudioTrack?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = coverTarget
        if (uri != null && target != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.getSharedPreferences("track_edits", Context.MODE_PRIVATE).edit()
                .putString("cover_${target.id}", uri.toString()).apply()
            tracks = loadTracks()
            selectedTrack = tracks.firstOrNull { it.id == target.id }
            coverTarget = null
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) tracks = loadTracks()
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

    val filtered = tracks.filter { it.customTitle.contains(query, true) || it.originalArtist.contains(query, true) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17251D), Night, Night)))) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TUNO MUSIC", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text(when (selectedTab) { 0 -> "Медиатека"; 1 -> "Плеер"; else -> service },
                        color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(16.dp))
            when (selectedTab) {
                0 -> {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск музыки", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Все треки", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${tracks.size} треков", color = Muted, fontSize = 13.sp)
                    }
                    if (tracks.isEmpty()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Green, modifier = Modifier.size(52.dp))
                            Text("Музыка пока не найдена", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Разреши доступ к аудиофайлам телефона", color = Muted, fontSize = 12.sp)
                            Button(onClick = { permissionLauncher.launch(permission) }) { Text("Открыть медиатеку") }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 14.dp)) {
                            items(filtered, key = { it.id }) { track ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    selectedTrack = track
                                    val c = controllerProvider()
                                    if (c != null) {
                                        val index = tracks.indexOf(track)
                                        c.setMediaItems(tracks.map { toMediaItem(it) }, index, 0L)
                                        c.prepare(); c.play(); playing = true
                                    }
                                    selectedTab = 1
                                }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CoverThumb(track.cover, 48)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(track.customTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Medium)
                                        Text(track.originalArtist, color = Muted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = {
                                        editingTrack = track; editTitle = track.customTitle
                                    }) { Icon(Icons.Default.Edit, "Переименовать", tint = Muted) }
                                    Icon(Icons.Default.PlayCircle, null, tint = Green, modifier = Modifier.size(26.dp))
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val track = selectedTrack
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(8.dp))
                        if (track == null) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("Выбери трек в медиатеке", color = Muted)
                            }
                        } else {
                            CoverLarge(track.cover)
                            Spacer(Modifier.height(18.dp))
                            Text(track.customTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.originalArtist, color = Muted, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AssistChip(onClick = { coverTarget = track; coverPicker.launch(arrayOf("image/*")) },
                                    label = { Text("Обложка") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                                AssistChip(onClick = { editingTrack = track; editTitle = track.customTitle },
                                    label = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                            }
                            Spacer(Modifier.height(20.dp))
                            Slider(value = if (duration > 0) position.coerceIn(0f, duration) else 0f,
                                onValueChange = { position = it },
                                onValueChangeFinished = { controllerProvider()?.seekTo((position * 1000).toLong()) },
                                valueRange = 0f..(duration.coerceAtLeast(1f)), colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(position), color = Muted, fontSize = 12.sp)
                                Text(formatTime(duration), color = Muted, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controllerProvider()?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) {
                                    Icon(Icons.Default.Shuffle, null, tint = Green, modifier = Modifier.size(25.dp))
                                }
                                IconButton(onClick = { controllerProvider()?.seekToPreviousMediaItem() }) {
                                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                }
                                IconButton(onClick = {
                                    controllerProvider()?.let { if (it.isPlaying) it.pause() else it.play() }
                                }, modifier = Modifier.size(68.dp).background(Green, CircleShape)) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Night,
                                        modifier = Modifier.size(42.dp))
                                }
                                IconButton(onClick = { controllerProvider()?.seekToNextMediaItem() }) {
                                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                }
                                IconButton(onClick = { controllerProvider()?.let { it.repeatMode =
                                    if (it.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) androidx.media3.common.Player.REPEAT_MODE_ALL
                                    else androidx.media3.common.Player.REPEAT_MODE_OFF } }) {
                                    Icon(Icons.Default.Repeat, null, tint = Green, modifier = Modifier.size(25.dp))
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = service == "SoundCloud", onClick = {
                            service = "SoundCloud"; serviceUrl = "https://soundcloud.com"
                        }, label = { Text("SoundCloud") })
                        FilterChip(selected = service == "Spotify", onClick = {
                            service = "Spotify"; serviceUrl = "https://open.spotify.com"
                        }, label = { Text("Spotify") })
                    }
                    OutlinedTextField(value = serviceQuery, onValueChange = { serviceQuery = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Найти трек или исполнителя", color = Muted) },
                        trailingIcon = { IconButton(onClick = {
                            val q = URLEncoder.encode(serviceQuery, "UTF-8")
                            serviceUrl = if (service == "Spotify") "https://open.spotify.com/search/$q"
                                else "https://soundcloud.com/search/sounds?q=$q"
                        }) { Icon(Icons.Default.Search, null, tint = Green) } },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Muted,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Spacer(Modifier.height(12.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(16.dp)),
                        factory = { ctx -> WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            webViewClient = WebViewClient()
                            loadUrl(serviceUrl)
                        } },
                        update = { web -> if (web.tag != serviceUrl) { web.tag = serviceUrl; web.loadUrl(serviceUrl) } }
                    )
                }
            }
        }
        NavigationBar(containerColor = Color(0xFF101713), tonalElevation = 0.dp) {
            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Музыка") })
            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Album, null) }, label = { Text("Плеер") })
            NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                icon = { Icon(Icons.Default.Language, null) }, label = { Text("Сервисы") })
        }
    }

    if (editingTrack != null) {
        AlertDialog(
            onDismissRequest = { editingTrack = null },
            title = { Text("Переименовать трек") },
            text = { OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, singleLine = true,
                label = { Text("Название") }) },
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

private fun toMediaItem(track: AudioTrack) = MediaItem.Builder().setUri(track.uri).setMediaMetadata(
    MediaMetadata.Builder().setTitle(track.customTitle).setArtist(track.originalArtist)
        .setArtworkUri(track.cover?.let { Uri.parse(it) }).build()
).build()

@Composable
private fun CoverThumb(uri: String?, size: Int) {
    if (uri != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_media_play),
            contentDescription = null, modifier = Modifier.size(size.dp).background(Panel, RoundedCornerShape(12.dp))
        )
    } else {
        Box(Modifier.size(size.dp).background(Panel, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Green)
        }
    }
}

@Composable
private fun CoverLarge(uri: String?) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 18.dp)
        .background(Brush.linearGradient(listOf(Color(0xFF315B43), Color(0xFF1A2B21), Color(0xFF4A6D55))),
            RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Green.copy(alpha = 0.65f), modifier = Modifier.size(110.dp))
    }
}

private fun formatTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
