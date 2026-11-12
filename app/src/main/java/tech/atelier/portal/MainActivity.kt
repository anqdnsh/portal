package tech.atelier.portal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101311)
private val Panel = Color(0xFF191E1B)
private val Raised = Color(0xFF252C27)
private val Accent = Color(0xFFB7C9F5)
private val Muted = Color(0xFF929A9F)
private val Paper = Color(0xFFF1F3F6)
private const val PREFS = "portal_shelf"
private const val KEY_URIS = "uris"

data class ShelfFile(val uri: Uri, val name: String, val mime: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalApp(sharedUris = extractUris(intent)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun extractUris(source: Intent?): List<Uri> {
        if (source == null) return emptyList()
        val result = mutableListOf<Uri>()
        source.data?.let(result::add)
        source.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(result::add)
        } else {
            @Suppress("DEPRECATION")
            (source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(result::add)
        }
        return result.distinct()
    }
}

@Composable
private fun PortalApp(sharedUris: List<Uri>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }
    fun describe(uri: Uri): ShelfFile {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Untitled"
        return ShelfFile(uri, name, context.contentResolver.getType(uri))
    }
    val files = remember {
        mutableStateListOf<ShelfFile>().apply {
            prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().forEach { raw ->
                runCatching { Uri.parse(raw) }.getOrNull()?.let { uri ->
                    runCatching { add(describe(uri)) }
                }
            }
        }
    }
    fun addUris(uris: List<Uri>, persist: Boolean) {
        uris.distinct().forEach { uri ->
            if (persist) runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (files.none { it.uri == uri }) runCatching { files.add(describe(uri)) }
        }
    }
    LaunchedEffect(sharedUris) { addUris(sharedUris, false) }
    LaunchedEffect(files.size, files.map { it.uri.toString() }) {
        prefs.edit().putStringSet(KEY_URIS, files.map { it.uri.toString() }.toSet()).apply()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addUris(uris, true)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Paper, secondary = Muted)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Raised, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Layers, null, tint = Accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("PORTAL", color = Paper, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
                        Text("BY ATELIER TECH", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("BETA 0.2", color = Accent, fontSize = 10.sp)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("A SPACE FOR YOUR FILES", color = Accent, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                        Text("Gather.\nMove. Continue.", color = Paper, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
                        Text("Collect files from your device or send them to PORTAL from another app.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add files to shelf", fontWeight = FontWeight.SemiBold)
                        }
                        Text(if (files.isEmpty()) "YOUR SHELF IS READY" else "${files.size} FILES COLLECTED", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR SHELF", color = Accent, fontSize = 10.sp, letterSpacing = 1.8.sp)
                        Text("Collected files", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (files.isNotEmpty()) IconButton(onClick = { files.clear() }) { Icon(Icons.Outlined.DeleteOutline, "Clear shelf", tint = Muted) }
                }
                if (files.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null, tint = Accent, modifier = Modifier.size(34.dp))
                            Text("Nothing here yet", color = Paper)
                            Text("Add files or share them to PORTAL.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(files, key = { it.uri.toString() }) { file ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Description, null, tint = Accent)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f).clickable {
                                        runCatching {
                                            val view = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(file.uri, file.mime ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(view)
                                        }
                                    }.padding(vertical = 8.dp)) {
                                        Text(file.name, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(file.mime ?: "File · Tap to open", color = Muted, fontSize = 10.sp)
                                    }
                                    IconButton(onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = file.mime ?: "*/*"
                                            putExtra(Intent.EXTRA_STREAM, file.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share from PORTAL"))
                                    }) { Icon(Icons.Outlined.OpenInNew, "Share file", tint = Muted) }
                                    IconButton(onClick = { files.remove(file) }) { Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Muted) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Raised)
                Row {
                    Text("ATELIER TECH", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.weight(1f))
                    Text("A GATEWAY TO WHAT'S NEXT", color = Muted, fontSize = 9.sp)
                }
            }
        }
    }
}
