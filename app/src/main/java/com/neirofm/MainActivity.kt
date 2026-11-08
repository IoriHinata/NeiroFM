package com.neirofm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.neirofm.playback.RadioPlaybackService
import com.neirofm.playback.RadioController

private val Ink = Color(0xFF101522)
private val Panel = Color(0xFF1B2435)
private val Accent = Color(0xFFFFB84D)
private val Muted = Color(0xFF9AA7BA)

class MainActivity : ComponentActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val files = DocumentFile.fromTreeUri(this, uri)?.listFiles()
                ?.filter { it.isFile && (it.name?.substringAfterLast('.', "")?.lowercase() in listOf("mp3","m4a","ogg","wav","flac")) }
                ?.map { it.uri.toString() } ?: emptyList()
            RadioController.setLibrary(this, files)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, RadioPlaybackService::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, background = Ink, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                RadioScreen(
                    onChooseFolder = { folderPicker.launch(null) },
                    onPlay = { RadioController.start(this) },
                    onPause = { RadioController.pause(this) },
                    onResume = { RadioController.resume(this) },
                    onStop = { RadioController.stop(this) }
                )
            }
        }
    }
}

@Composable
private fun RadioScreen(
    onChooseFolder: () -> Unit, onPlay: () -> Unit, onPause: () -> Unit,
    onResume: () -> Unit, onStop: () -> Unit
) {
    var playing by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf("NEIRO FM • OFFLINE RADIO") }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NEIRO", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("FM  /  YOUR PERSONAL RADIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(34.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Panel, RoundedCornerShape(28.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(210.dp).background(Color(0xFF252F42), RoundedCornerShape(105.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(164.dp).background(Ink, RoundedCornerShape(82.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Radio, null, tint = Accent, modifier = Modifier.size(94.dp))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(if (playing) "ON AIR" else "STANDBY", color = if (playing) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Text(station, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (playing) "Эфир запущен" else "Нажми, чтобы начать эфир", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (playing) { onPause(); playing = false } else { onPlay(); playing = true } }, modifier = Modifier.weight(1f).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent), shape = RoundedCornerShape(18.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Ink)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "ПАУЗА" else "В ЭФИР", color = Ink, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { onStop(); playing = false }, modifier = Modifier.size(58.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Default.Stop, "Остановить", tint = Color.White)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Выбрать папку с музыкой")
        }
        Spacer(Modifier.height(10.dp))
        Text("Музыка хранится на устройстве • Интернет не требуется", color = Muted, fontSize = 11.sp)
    }
}
