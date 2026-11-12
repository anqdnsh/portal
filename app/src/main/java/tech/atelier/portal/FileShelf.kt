package tech.atelier.portal

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

data class ShelfFile(val id: String, val fileName: String, val displayName: String, val mime: String)

object FileShelf {
    private const val PREFS = "portal_shelf"
    private const val KEY_FILES = "files"

    fun all(context: Context): List<ShelfFile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FILES, emptySet()).orEmpty()
        return raw.mapNotNull { value ->
            val p = value.split('|', limit = 4)
            if (p.size != 4) return@mapNotNull null
            val file = File(context.filesDir, "portal_shelf/" + p[1])
            if (file.exists()) ShelfFile(p[0], p[1], p[2], p[3]) else null
        }.sortedByDescending { it.id }
    }

    fun importUris(context: Context, uris: List<Uri>): Int {
        val dir = File(context.filesDir, "portal_shelf").apply { mkdirs() }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val records = prefs.getStringSet(KEY_FILES, emptySet()).orEmpty().toMutableSet()
        var added = 0
        uris.distinct().forEach { uri ->
            runCatching {
                val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: uri.lastPathSegment ?: "Untitled"
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val id = UUID.randomUUID().toString()
                val ext = displayName.substringAfterLast('.', "").takeIf { it.length in 1..8 }?.let { ".$it" } ?: ""
                val stored = id + ext
                val target = File(dir, stored)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to read selected file")
                records.add(listOf(id, stored, displayName.replace("|", "_"), mime).joinToString("|"))
                added++
            }
        }
        prefs.edit().putStringSet(KEY_FILES, records).apply()
        return added
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val records = prefs.getStringSet(KEY_FILES, emptySet()).orEmpty().toMutableSet()
        val record = records.firstOrNull { it.startsWith("$id|") } ?: return
        val parts = record.split('|', limit = 4)
        if (parts.size > 1) File(context.filesDir, "portal_shelf/" + parts[1]).delete()
        records.remove(record)
        prefs.edit().putStringSet(KEY_FILES, records).apply()
    }

    fun clear(context: Context) {
        File(context.filesDir, "portal_shelf").deleteRecursively()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FILES).apply()
    }

    fun uri(context: Context, file: ShelfFile): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", File(context.filesDir, "portal_shelf/" + file.fileName))
}