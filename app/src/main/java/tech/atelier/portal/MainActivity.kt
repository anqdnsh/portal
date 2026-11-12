package tech.atelier.portal

import android.content.Intent
import android.content.ClipData
import android.view.View
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101216)
private val Panel = Color(0xFF191D24)
private val Raised = Color(0xFF252B35)
private val Accent = Color(0xFF4D8DFF)
private val Muted = Color(0xFF929BAA)
private val Paper = Color(0xFFF4F6FA)




class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalApp(sharedUris = extractUris(intent)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { PortalApp(sharedUris = extractUris(intent)) }
    }

    private fun extractUris(source: Intent?): List<Uri> {
        if (source == null) return emptyList()
        val result = mutableListOf<Uri>()
        source.data?.let(result::add)
        source.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add) }
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val streams = source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            streams?.let(result::addAll)
        } else if (android.os.Build.VERSION.SDK_INT >= 33) {
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
    val rootView = LocalView.current
    var files by remember { mutableStateOf(FileShelf.all(context)) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    fun refresh() { files = FileShelf.all(context) }
    val overlaySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayEnabled = Settings.canDrawOverlays(context)
        if (overlayEnabled) {
            val i = Intent(context, PortalOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
    fun toggleOverlay() {
        if (overlayEnabled) {
            context.stopService(Intent(context, PortalOverlayService::class.java))
            overlayEnabled = false
        } else if (Settings.canDrawOverlays(context)) {
            val i = Intent(context, PortalOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            overlayEnabled = true
        } else overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
    }
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) FileShelf.importUris(context, sharedUris)
        refresh()
    }
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("portal_shelf", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) FileShelf.importUris(context, uris)
        refresh()
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
                    Text("0.3 BETA", color = Accent, fontSize = 10.sp)
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
                        Text("${files.size} FILES IN YOUR SHELF", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Raised), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Widgets, contentDescription = null, tint = Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Floating shelf", color = Paper, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(if (overlayEnabled) "Shortcut is active" else "Keep a small PORTAL tab above apps", color = Muted, fontSize = 10.sp)
                        }
                        TextButton(onClick = { toggleOverlay() }) {
                            Text(if (overlayEnabled) "Turn off" else "Enable", color = Accent)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR SHELF", color = Accent, fontSize = 10.sp, letterSpacing = 1.8.sp)
                        Text("Collected files", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (files.isNotEmpty()) IconButton(onClick = { FileShelf.clear(context); refresh() }) { Icon(Icons.Outlined.DeleteOutline, "Clear shelf", tint = Muted) }
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
                        items(files, key = { it.id }) { file ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Description, null, tint = Accent)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f).clickable {
                                        runCatching {
                                            val view = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(FileShelf.uri(context, file), file.mime)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(view)
                                        }
                                    }.pointerInput(file.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                runCatching {
                                                    val fileUri = FileShelf.uri(context, file)
                                                    val clip = ClipData.newUri(context.contentResolver, file.displayName, fileUri)
                                                    rootView.startDragAndDrop(clip, View.DragShadowBuilder(rootView), null,
                                                        View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
                                                }
                                            },
                                            onDrag = { change, _ -> change.consume() }
                                        )
                                    }.padding(vertical = 8.dp)) {
                                        Text(file.displayName, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(file.mime, color = Muted, fontSize = 10.sp)
                                    }
                                    IconButton(onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = file.mime ?: "*/*"
                                            putExtra(Intent.EXTRA_STREAM, FileShelf.uri(context, file))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share from PORTAL"))
                                    }) { Icon(Icons.Outlined.OpenInNew, "Share file", tint = Muted) }
                                    IconButton(onClick = { FileShelf.remove(context, file.id); refresh() }) { Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Muted) }
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
