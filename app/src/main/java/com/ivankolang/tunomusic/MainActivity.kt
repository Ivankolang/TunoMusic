package com.ivankolang.tunomusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

private val Night = Color(0xFF080B0A)
private val Panel = Color(0xFF151B18)
private val Green = Color(0xFF9BE7B1)
private val Muted = Color(0xFF9AA69E)

data class AudioTrack(val id: Long, val title: String, val artist: String, val uri: Uri)

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
        val result = mutableListOf<AudioTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Без названия"
                val artist = cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Неизвестный исполнитель"
                val uri = Uri.withAppendedPath(collection, id.toString())
                result += AudioTrack(id, title, artist, uri)
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
    controllerProvider: () -> MediaController?,
    loadTracks: () -> List<AudioTrack>
) {
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("Выбери свой трек") }
    var currentArtist by remember { mutableStateOf("Твоя музыка начинается здесь") }
    var isPlaying by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val controller = controllerProvider()

    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                     else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tracks = loadTracks()
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            tracks = loadTracks()
        }
    }

    val filtered = tracks.filter {
        it.title.contains(query, true) || it.artist.contains(query, true)
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF17251D), Night, Night))
        ).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TUNO", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text(if (selectedTab == 0) "Твоя музыка" else "Платформы", color = Color.White,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(48.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.GraphicEq, null, tint = Green, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        if (selectedTab == 0) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск музыки", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF344139),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = Green
                )
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Медиатека", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${tracks.size} треков", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (tracks.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.LibraryMusic, null, tint = Green, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Музыка пока не найдена", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Разреши доступ к аудиофайлам телефона", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { permissionLauncher.launch(permission) }) {
                        Text("Открыть медиатеку")
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(filtered, key = { it.id }) { track ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val c = controllerProvider()
                                if (c != null) {
                                    val index = tracks.indexOf(track)
                                    c.setMediaItems(tracks.map {
                                        MediaItem.Builder().setUri(it.uri).setMediaMetadata(
                                            MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()
                                        ).build()
                                    }, index, 0L)
                                    c.prepare()
                                    c.play()
                                    currentTitle = track.title
                                    currentArtist = track.artist
                                    isPlaying = true
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).background(Panel, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, null, tint = Green)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium)
                                Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(Icons.Default.PlayCircle, null, tint = Green, modifier = Modifier.size(27.dp))
                        }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                PlatformCard("YouTube Music", "Открывать треки в официальном приложении", Icons.Default.PlayCircle) {
                    openPlatform("com.google.android.apps.youtube.music")
                }
                Spacer(Modifier.height(12.dp))
                PlatformCard("Spotify", "Открывать Spotify и его музыкальную библиотеку", Icons.Default.Headphones) {
                    openPlatform("com.spotify.music")
                }
                Spacer(Modifier.height(16.dp))
                Text("Платформы управляют своей музыкой самостоятельно. Прямое воспроизведение внутри Tuno зависит от официальных API и разрешений сервисов.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        PlayerPanel(currentTitle, currentArtist, isPlaying,
            onPlayPause = {
                val c = controllerProvider()
                if (c != null) {
                    if (c.isPlaying) { c.pause(); isPlaying = false }
                    else if (c.mediaItemCount > 0) { c.play(); isPlaying = true }
                }
            },
            onNext = { controllerProvider()?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
            onPrevious = { controllerProvider()?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }
        )
        Spacer(Modifier.height(10.dp))
        NavigationBar(containerColor = Color(0xFF101713), tonalElevation = 0.dp) {
            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Моя музыка") })
            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Explore, null) }, label = { Text("Платформы") })
        }
    }
}

@Composable
private fun PlatformCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Default.OpenInNew, null, tint = Muted)
    }
}

@Composable
private fun PlayerPanel(
    title: String, artist: String, playing: Boolean,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(
        Brush.horizontalGradient(listOf(Color(0xFF1D3828), Color(0xFF17231C))),
        RoundedCornerShape(22.dp)
    ).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(0xFF2D4D38), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Album, null, tint = Green, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(artist, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = onPlayPause) {
                Icon(if (playing) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                    null, tint = Green, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
    }
}

private fun MainActivity.openPlatform(packageName: String) {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    if (launch != null) startActivity(launch)
    else {
        val url = when (packageName) {
            "com.spotify.music" -> "https://open.spotify.com"
            else -> "https://music.youtube.com"
        }
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
