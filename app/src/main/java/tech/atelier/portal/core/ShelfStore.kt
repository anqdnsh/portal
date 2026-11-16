package tech.atelier.portal.core

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One thing kept on the shelf: a file copied into Portal's private storage, or a piece of text. */
data class ShelfItem(
    val id: String,
    val file: String,       // stored file name inside the shelf folder ("" for text)
    val name: String,       // what the user sees
    val mime: String,
    val size: Long,
    val added: Long,
    val pinned: Boolean,
    val text: String?,      // non-null for text and links
) {
    val isText get() = text != null
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf"
    val isLink get() = text != null && LINK.matches(text.trim())
    val ext: String
        get() = when {
            isLink -> "LINK"
            isText -> "TEXT"
            else -> name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
        }

    companion object {
        private val LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    }
}

/**
 * Something an action works on: an item already on the shelf, or something just dropped
 * from another app (copied into a staging folder first, because drop permissions are short-lived).
 */
class Payload(val name: String, val mime: String, val file: File?, val uri: Uri?, val text: String?)

/** Content picked up from a drop or a share, with its stream already open. */
class Incoming(val name: String, val mime: String, val input: InputStream?, val text: String?)

/**
 * The shelf's contents. Every change happens on the main thread; copying happens on [io].
 * Saved as a small JSON file, so the shelf survives restarts.
 */
object ShelfStore {
    private const val DIR = "portal_shelf"
    private const val STAGE = "portal_stage"

    private lateinit var app: Context
    private val items = mutableListOf<ShelfItem>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        ready = true
        load()
        migrateOld()
        applyAutoClear()
        io.execute { cleanStage() }
    }

    fun dir(): File = File(app.filesDir, DIR).apply { mkdirs() }
    fun stageDir(): File = File(app.cacheDir, STAGE).apply { mkdirs() }
    fun fileOf(item: ShelfItem): File = File(dir(), item.file)

    fun all(): List<ShelfItem> = items.toList()
    fun count(): Int = items.size
    fun get(id: String): ShelfItem? = items.firstOrNull { it.id == id }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun payloadOf(item: ShelfItem): Payload =
        if (item.isText) Payload(item.name, item.mime, null, null, item.text)
        else Payload(item.name, item.mime, fileOf(item), ShelfProvider.itemUri(item), null)

    // ---------- reading what was dropped or shared ----------

    /** Opens every item of a clip right away, while the drop permission is still valid. */
    fun open(ctx: Context, clip: ClipData): List<Incoming> {
        val out = mutableListOf<Incoming>()
        for (i in 0 until clip.itemCount) {
            val it = clip.getItemAt(i)
            val uri = it.uri
            if (uri != null) {
                openUri(ctx, uri)?.let(out::add)
            } else {
                val t = it.text?.toString() ?: it.htmlText
                if (!t.isNullOrBlank()) out.add(Incoming(textName(t), "text/plain", null, t))
            }
        }
        return out
    }

    fun openUri(ctx: Context, uri: Uri): Incoming? = try {
        val cr = ctx.contentResolver
        var name: String? = null
        if (uri.scheme == "content") {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        val n = (name ?: uri.lastPathSegment ?: "Untitled").replace('/', '_')
        val mime = cr.getType(uri) ?: guessMime(n)
        val input = cr.openInputStream(uri) ?: throw IllegalStateException("no stream")
        Incoming(n, mime, input, null)
    } catch (_: Exception) {
        null
    }

    fun textName(t: String): String {
        val line = t.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return if (line.length > 40) line.take(39) + "…" else line.ifEmpty { "Text" }
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Copies incoming content onto the shelf. [done] runs on the main thread with the new items. */
    fun keep(list: List<Incoming>, done: (List<ShelfItem>) -> Unit = {}) {
        if (list.isEmpty()) { done(emptyList()); return }
        io.execute {
            val made = list.mapNotNull { write(it) }
            main.post {
                items.addAll(0, made.reversed())
                changed()
                done(made)
            }
        }
    }

    private fun write(inc: Incoming): ShelfItem? {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        if (inc.text != null) {
            return ShelfItem(id, "", inc.name, "text/plain", inc.text.length.toLong(), now, false, inc.text)
        }
        val input = inc.input ?: return null
        val stored = id + extOf(inc.name)
        val target = File(dir(), stored)
        return try {
            input.use { s -> target.outputStream().use { o -> s.copyTo(o, 64 * 1024) } }
            ShelfItem(id, stored, inc.name, inc.mime, target.length(), now, false, null)
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    /** Copies incoming content into the staging folder (for actions on dropped things). Runs on [io]. */
    fun stage(list: List<Incoming>): List<Payload> = list.mapNotNull { inc ->
        if (inc.text != null) return@mapNotNull Payload(inc.name, inc.mime, null, null, inc.text)
        val input = inc.input ?: return@mapNotNull null
        val folder = File(stageDir(), UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val f = File(folder, safeName(inc.name))
        try {
            input.use { s -> f.outputStream().use { o -> s.copyTo(o, 64 * 1024) } }
            Payload(inc.name, inc.mime, f, ShelfProvider.stageUri(f), null)
        } catch (_: Exception) {
            null
        }
    }

    /** Takes a file an action produced (on [io]) and puts it on the shelf. */
    fun adopt(src: File, name: String, mime: String, done: (ShelfItem?) -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        val stored = id + extOf(name)
        val target = File(dir(), stored)
        val ok = src.renameTo(target) || runCatching { src.copyTo(target, true); src.delete(); true }.getOrDefault(false)
        val item = if (ok) ShelfItem(id, stored, name, mime, target.length(), System.currentTimeMillis(), false, null) else null
        main.post {
            if (item != null) {
                items.add(0, item)
                changed()
            }
            done(item)
        }
    }

    /** A fresh file in the staging folder for an action's output. */
    fun scratch(name: String): File {
        val folder = File(stageDir(), "out-" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        return File(folder, safeName(name))
    }

    // ---------- changes ----------

    fun remove(id: String) {
        val item = get(id) ?: return
        if (!item.isText) fileOf(item).delete()
        items.remove(item)
        changed()
    }

    /** Removes everything that isn't pinned. */
    fun clearUnpinned() {
        val gone = items.filter { !it.pinned }
        if (gone.isEmpty()) return
        gone.forEach { if (!it.isText) fileOf(it).delete() }
        items.removeAll(gone)
        changed()
    }

    fun togglePin(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].copy(pinned = !items[i].pinned)
        changed()
    }

    fun applyAutoClear() {
        val h = Prefs.autoClearHours(app)
        if (h <= 0) return
        val cutoff = System.currentTimeMillis() - h * 3_600_000L
        val gone = items.filter { !it.pinned && it.added < cutoff }
        if (gone.isEmpty()) return
        gone.forEach { if (!it.isText) fileOf(it).delete() }
        items.removeAll(gone)
        changed()
    }

    private fun changed() {
        val snapshot = items.toList()
        io.execute { save(snapshot) }
        listeners.forEach { it() }
    }

    // ---------- saving ----------

    private fun indexFile() = File(app.filesDir, "shelf.json")

    private fun save(list: List<ShelfItem>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("file", s.file); put("name", s.name); put("mime", s.mime)
                put("size", s.size); put("added", s.added); put("pinned", s.pinned)
                if (s.text != null) put("text", s.text)
            })
        }
        val tmp = File(app.filesDir, "shelf.json.tmp")
        runCatching {
            tmp.writeText(arr.toString())
            tmp.renameTo(indexFile())
        }
    }

    private fun load() {
        items.clear()
        val f = indexFile()
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = ShelfItem(
                    o.getString("id"), o.optString("file"), o.optString("name", "Untitled"),
                    o.optString("mime", "application/octet-stream"), o.optLong("size"),
                    o.optLong("added"), o.optBoolean("pinned"),
                    if (o.has("text")) o.getString("text") else null,
                )
                if (item.isText || fileOf(item).exists()) items.add(item)
            }
        }
    }

    /** Version 0.3 kept its list in preferences ("id|stored|name|mime"). Bring those files across once. */
    private fun migrateOld() {
        val old = app.getSharedPreferences("portal_shelf", Context.MODE_PRIVATE)
        val raw = old.getStringSet("files", null) ?: return
        var moved = false
        raw.forEach { v ->
            val p = v.split('|', limit = 4)
            if (p.size == 4 && items.none { it.id == p[0] }) {
                val f = File(dir(), p[1])
                if (f.exists()) {
                    items.add(ShelfItem(p[0], p[1], p[2], p[3], f.length(), f.lastModified(), false, null))
                    moved = true
                }
            }
        }
        old.edit().remove("files").apply()
        if (moved) changed()
    }

    private fun cleanStage() {
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        stageDir().listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }?.let { ".$it" } ?: ""

    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "file" }
}
