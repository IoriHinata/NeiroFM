package com.neirofm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.documentfile.provider.DocumentFile
import com.neirofm.playback.RadioController
import com.neirofm.playback.RadioPlaybackService

private val Ink = Color(0xFF101522)
private val Panel = Color(0xFF1B2435)
private val Accent = Color(0xFFFFB84D)
private val Muted = Color(0xFF9AA7BA)

class MainActivity : ComponentActivity() {
    private var pickerType = "music"
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val files = DocumentFile.fromTreeUri(this, uri)?.listFiles()
                ?.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in listOf("mp3", "m4a", "ogg", "wav", "flac", "aac") }
                ?.map { it.uri.toString() }.orEmpty()
            RadioController.setLibrary(this, pickerType, files)
            setContentViewAgain()
        }
    }

    private fun setContentViewAgain() {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
                RadioScreen(
                    musicCount = RadioController.library(this, "music").size,
                    podcastCount = RadioController.library(this, "podcasts").size,
                    adCount = RadioController.library(this, "ads").size,
                    onChoose = { type -> pickerType = type; folderPicker.launch(null) },
                    onPlay = { RadioController.start(this) },
                    onPause = { RadioController.pause(this) },
                    onStop = { RadioController.stop(this) },
                    onTestVoice = { RadioController.testVoice(this) }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, RadioPlaybackService::class.java))
        setContentViewAgain()
    }
}

@Composable
private fun RadioScreen(
    musicCount: Int, podcastCount: Int, adCount: Int,
    onChoose: (String) -> Unit, onPlay: () -> Unit, onPause: () -> Unit,
    onStop: () -> Unit, onTestVoice: () -> Unit
) {
    var playing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NEIRO FM", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("OFFLINE RADIO • PERSONAL BROADCAST", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(260.dp).background(Panel, RoundedCornerShape(28.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp).background(Color(0xFF252F42), RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Radio, null, tint = Accent, modifier = Modifier.size(86.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(if (playing) "ON AIR" else "STANDBY", color = if (playing) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Три ведущих • музыка • передачи", color = Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (playing) { onPause(); playing = false } else { onPlay(); playing = true } }, modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent), shape = RoundedCornerShape(16.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Ink)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "ПАУЗА" else "В ЭФИР", color = Ink, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { onStop(); playing = false }, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Stop, "Остановить")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onTestVoice, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.RecordVoiceOver, null)
            Spacer(Modifier.width(8.dp))
            Text("Проверить голос диктора")
        }
        Spacer(Modifier.height(18.dp))
        Text("МОЯ БИБЛИОТЕКА", modifier = Modifier.fillMaxWidth(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        LibraryRow("Музыка", musicCount, "music", onChoose)
        LibraryRow("Подкасты", podcastCount, "podcasts", onChoose)
        LibraryRow("Рекламные аудио", adCount, "ads", onChoose)
        Spacer(Modifier.height(12.dp))
        Text("Все выбранные файлы воспроизводятся локально. Для русской речи нужен установленный офлайн-голос Android.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun LibraryRow(title: String, count: Int, type: String, onChoose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Panel, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, tint = Accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("$count файлов", color = Muted, fontSize = 11.sp)
        }
        TextButton(onClick = { onChoose(type) }) { Text("Выбрать") }
    }
}
