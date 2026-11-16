package tech.atelier.portal.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import tech.atelier.portal.core.ShelfItem
import tech.atelier.portal.core.ShelfStore
import java.util.concurrent.Executors

/** Small previews for images, videos and PDFs, made off the main thread and kept in memory. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()

    fun peek(item: ShelfItem): Bitmap? = cache.get(item.id)

    fun canPreview(item: ShelfItem) =
        !item.isText && (item.isImage || item.mime.startsWith("video/") || item.isPdf)

    fun load(item: ShelfItem, px: Int, ready: (Bitmap) -> Unit) {
        if (!canPreview(item)) return
        cache.get(item.id)?.let { ready(it); return }
        if (!pending.add(item.id)) return
        val file = ShelfStore.fileOf(item)
        pool.execute {
            val bmp = try {
                when {
                    item.isImage -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        val scale = px.toFloat() / minOf(w, h).coerceAtLeast(1)
                        if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    item.mime.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
                    else -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { r ->
                            r.openPage(0).use { pg ->
                                val scale = px.toFloat() / minOf(pg.width, pg.height).coerceAtLeast(1)
                                val b = Bitmap.createBitmap((pg.width * scale).toInt().coerceAtLeast(1), (pg.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
            main.post {
                pending.remove(item.id)
                if (bmp != null) {
                    cache.put(item.id, bmp)
                    ready(bmp)
                }
            }
        }
    }
}
