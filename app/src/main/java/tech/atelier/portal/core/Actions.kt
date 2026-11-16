package tech.atelier.portal.core

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The quick actions on the shelf. Slots 1 and 2 are fixed; slot 3 is picked in settings. */
enum class Action(val id: String, val label: String) {
    QUICK_SHARE("quick_share", "Quick Share"),
    COMPRESS("compress", "Compress"),
    SHARE(Prefs.SLOT_SHARE, "Share to apps"),
    CONVERT(Prefs.SLOT_CONVERT, "Convert to JPG"),
    MERGE(Prefs.SLOT_MERGE, "Merge PDFs"),
    COPY(Prefs.SLOT_COPY, "Copy");

    companion object {
        fun slot3(ctx: Context): Action = entries.firstOrNull { it.id == Prefs.slot3(ctx) && it.ordinal >= 2 } ?: SHARE
    }
}

object Actions {
    private val main = Handler(Looper.getMainLooper())

    /** Samsung Quick Share, then Google's Quick Share. Falls back to the normal share sheet. */
    private val QUICK_SHARE_PACKAGES = listOf("com.samsung.android.app.sharelive", "com.google.android.gms")

    /**
     * Runs [action] on [items]. [done] gets a short status line (or null) on the main thread.
     * [displayId] is where any app or share sheet should open (matters with DeX on a monitor).
     */
    fun run(ctx: Context, action: Action, items: List<Payload>, displayId: Int, done: (String?) -> Unit) {
        if (items.isEmpty()) { done("Nothing to ${action.label.lowercase()}"); return }
        when (action) {
            Action.QUICK_SHARE -> done(quickShare(ctx, items, displayId))
            Action.SHARE -> done(shareSheet(ctx, items, displayId))
            Action.COPY -> done(copy(ctx, items))
            Action.COMPRESS -> background(done) { compress(items) }
            Action.CONVERT -> background(done) { convert(items) }
            Action.MERGE -> background(done) { merge(items) }
        }
    }

    private fun background(done: (String?) -> Unit, work: () -> String?) {
        ShelfStore.io.execute {
            val msg = try { work() } catch (e: Throwable) { "Couldn't finish: " + (e.message ?: "error") }
            main.post { done(msg) }
        }
    }

    // ---------- sharing ----------

    fun shareIntent(ctx: Context, items: List<Payload>): Intent {
        val files = items.filter { it.uri != null }
        val texts = items.mapNotNull { it.text }
        val intent: Intent
        if (files.isEmpty()) {
            intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n"))
        } else {
            val uris = ArrayList(files.mapNotNull { it.uri })
            val type = commonMime(files.map { it.mime })
            intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            if (texts.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n"))
            val clip = ClipData.newUri(ctx.contentResolver, files[0].name, uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    private fun commonMime(mimes: List<String>): String {
        val distinct = mimes.distinct()
        if (distinct.size == 1) return distinct[0]
        val tops = distinct.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1) tops[0] + "/*" else "*/*"
    }

    private fun quickShare(ctx: Context, items: List<Payload>, displayId: Int): String? {
        val pm = ctx.packageManager
        for (pkg in QUICK_SHARE_PACKAGES) {
            val i = shareIntent(ctx, items).setPackage(pkg)
            val hits = pm.queryIntentActivities(i, 0)
            if (hits.isEmpty()) continue
            // Google Play services has many share targets; pick its Quick Share one by name.
            val pick = if (pkg == "com.google.android.gms") {
                hits.firstOrNull { it.activityInfo.name.contains("nearby", true) || it.activityInfo.name.contains("sharing", true) }
                    ?: continue
            } else hits[0]
            i.component = ComponentName(pick.activityInfo.packageName, pick.activityInfo.name)
            if (launch(ctx, i, displayId)) return null
        }
        val err = shareSheet(ctx, items, displayId)
        return err ?: "Quick Share not found, opened the share sheet"
    }

    private fun shareSheet(ctx: Context, items: List<Payload>, displayId: Int): String? {
        val chooser = Intent.createChooser(shareIntent(ctx, items), "Share from Portal")
        return if (launch(ctx, chooser, displayId)) null else "Couldn't open the share sheet"
    }

    fun open(ctx: Context, item: Payload, displayId: Int): Boolean {
        val uri = item.uri ?: return false
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(ctx, Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), displayId)
    }

    fun openLink(ctx: Context, link: String, displayId: Int): Boolean =
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(link.trim())), displayId)

    fun launch(ctx: Context, intent: Intent, displayId: Int): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        ctx.startActivity(intent, opts.toBundle())
        true
    } catch (_: Exception) {
        try { ctx.startActivity(intent); true } catch (_: Exception) { false }
    }

    // ---------- copy ----------

    private fun copy(ctx: Context, items: List<Payload>): String {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val texts = items.mapNotNull { it.text }
        val clip = if (texts.size == items.size) {
            ClipData.newPlainText("Portal", texts.joinToString("\n"))
        } else {
            val files = items.filter { it.uri != null }
            val c = ClipData.newUri(ctx.contentResolver, files[0].name, files[0].uri!!)
            for (i in 1 until files.size) c.addItem(ClipData.Item(files[i].uri!!))
            texts.forEach { c.addItem(ClipData.Item(it)) }
            c
        }
        cm.setPrimaryClip(clip)
        return if (items.size == 1) "Copied" else "Copied ${items.size} items"
    }

    // ---------- compress ----------

    private fun compress(items: List<Payload>): String {
        val name = if (items.size == 1) items[0].name.substringBeforeLast('.') + ".zip" else "Portal ${items.size} items.zip"
        val out = ShelfStore.scratch(name)
        val used = HashSet<String>()
        fun unique(n: String): String {
            var candidate = n
            var k = 2
            while (!used.add(candidate.lowercase())) {
                candidate = n.substringBeforeLast('.') + " ($k)" + (n.substringAfterLast('.', "").let { if (it.isEmpty() || it == n) "" else ".$it" })
                k++
            }
            return candidate
        }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            items.forEach { p ->
                if (p.text != null) {
                    zip.putNextEntry(ZipEntry(unique(ShelfStore.safeName(p.name) + ".txt")))
                    zip.write(p.text.toByteArray())
                } else {
                    val f = p.file ?: return@forEach
                    zip.putNextEntry(ZipEntry(unique(ShelfStore.safeName(p.name))))
                    f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                }
                zip.closeEntry()
            }
        }
        ShelfStore.adopt(out, name, "application/zip")
        return "Zipped → $name"
    }

    // ---------- convert ----------

    private fun convert(items: List<Payload>): String {
        val images = items.filter { it.mime.startsWith("image/") && it.file != null }
        if (images.isEmpty()) return "No images to convert"
        var n = 0
        images.forEach { p ->
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(p.file!!)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val name = p.name.substringBeforeLast('.') + ".jpg"
            val out = ShelfStore.scratch(name)
            // JPG has no transparency: put the image on white first.
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            out.outputStream().buffered().use { flat.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            flat.recycle()
            ShelfStore.adopt(out, name, "image/jpeg")
            n++
        }
        return if (n == 1) "Converted to JPG" else "Converted $n images to JPG"
    }

    // ---------- merge ----------

    /** Joins PDFs into one. Android has no PDF editor built in, so pages are re-drawn at 2× (144 dpi). */
    private fun merge(items: List<Payload>): String {
        val pdfs = items.filter { it.mime == "application/pdf" && it.file != null }
        if (pdfs.isEmpty()) return "No PDFs to merge"
        val doc = PdfDocument()
        var pageNo = 0
        try {
            pdfs.forEach { p ->
                ParcelFileDescriptor.open(p.file!!, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        for (i in 0 until r.pageCount) {
                            r.openPage(i).use { src ->
                                val w = src.width
                                val h = src.height
                                val bmp = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create())
                                page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                                doc.finishPage(page)
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
            val name = if (pdfs.size == 1) pdfs[0].name.substringBeforeLast('.') + " copy.pdf" else "Merged ${pdfs.size} PDFs.pdf"
            val out: File = ShelfStore.scratch(name)
            out.outputStream().buffered().use { doc.writeTo(it) }
            ShelfStore.adopt(out, name, "application/pdf")
            return "Merged $pageNo pages"
        } finally {
            doc.close()
        }
    }
}
