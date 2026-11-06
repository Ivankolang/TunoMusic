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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                result += AudioTrack(id, title, artist, Uri.withAppendedPath(collection, id.toString()),
                    prefs.getString("title_$id", title) ?: title, prefs.getString("cover_$id", null), prefs.getBoolean("favorite_$id", false))
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
    var tab by remember { mutableIntStateOf(0) }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var fullPlayer by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var service by remember { mutableStateOf("SoundCloud") }
    var serviceUrl by remember { mutableStateOf("") }
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

    BackHandler(enabled = fullPlayer) { fullPlayer = false }
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

    if (fullPlayer) {
        FullPlayerScreen(
            track = selectedTrack, playing = playing, position = position, duration = duration,
            onBack = { fullPlayer = false },
            onPlayPause = { controllerProvider()?.let { if (it.isPlaying) it.pause() else it.play() } },
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
                        Text(when (tab) { 0 -> "Твоя музыка"; 1 -> "Избранное"; else -> "Поиск музыки" }, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
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
                                    IconButton(onClick = { editingTrack = track; editTitle = track.customTitle }) {
                                        Icon(Icons.Default.MoreVert, null, tint = Muted)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SoundCloud", color = Green, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Text("Встроенный поиск", color = Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Трек, исполнитель или альбом", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        trailingIcon = { IconButton(onClick = {
                            val q = URLEncoder.encode(query.trim(), "UTF-8")
                            if (q.isNotBlank()) serviceUrl = "https://soundcloud.com/search/sounds?q=$q"
                        }) { Icon(Icons.Default.ArrowForward, null, tint = Green) } },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Green)
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
                            modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(16.dp)),
                            factory = { ctx -> WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                setBackgroundColor(android.graphics.Color.rgb(8, 11, 10))
                                webViewClient = WebViewClient()
                                loadUrl(serviceUrl)
                            } },
                            update = { web -> if (web.tag != serviceUrl) { web.tag = serviceUrl; web.loadUrl(serviceUrl) } }
                        )
                    }
                }
            }

            if (selectedTrack != null) {
                MiniPlayer(
                    track = selectedTrack!!, playing = playing,
                    onOpen = { fullPlayer = true },
                    onPlayPause = { controllerProvider()?.let { if (it.isPlaying) it.pause() else it.play() } },
                    onNext = { controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
                )
            }
            NavigationBar(containerColor = Color(0xFF101713), tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Медиатека") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Избранное") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text("Поиск") })
            }
        }
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
    val entranceScale = remember { Animatable(0.94f) }
    val entranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { entranceScale.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
            launch { entranceAlpha.animateTo(1f, tween(360)) }
        }
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
    Column(Modifier.fillMaxSize().graphicsLayer {
        scaleX = entranceScale.value
        scaleY = entranceScale.value
        alpha = entranceAlpha.value
    }.background(Brush.verticalGradient(listOf(Color(0xFF1B3526), Night, Night))).padding(horizontal = 24.dp)) {
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

private fun toMediaItem(track: AudioTrack) = MediaItem.Builder().setUri(track.uri).setMediaMetadata(
    MediaMetadata.Builder().setTitle(track.customTitle).setArtist(track.originalArtist)
        .setArtworkUri(track.cover?.let { Uri.parse(it) }).build()
).build()

private fun formatTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
