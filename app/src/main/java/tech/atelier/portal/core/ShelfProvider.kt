package tech.atelier.portal.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands shelf files to other apps (drag out, share, Quick Share) as read-only content:// links.
 * Not exported: apps can only read a file Portal has granted them, through a drag or a share.
 *
 * Paths: /i/<stored file>/<display name>  and  /s/<stage folder>/<file name>
 */
class ShelfProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "tech.atelier.portal.shelf"

        fun itemUri(item: ShelfItem): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("i").appendPath(item.file).appendPath(item.name).build()

        fun stageUri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("s").appendPath(f.parentFile?.name ?: "").appendPath(f.name).build()
    }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): Pair<File, String> {
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        if (seg.size != 3) throw FileNotFoundException(uri.toString())
        val (root, name) = when (seg[0]) {
            "i" -> File(ctx.filesDir, "portal_shelf") to seg[2]
            "s" -> File(ctx.cacheDir, "portal_stage/" + seg[1]) to seg[2]
            else -> throw FileNotFoundException(uri.toString())
        }
        val f = File(root, if (seg[0] == "i") seg[1] else seg[2])
        // Never serve anything outside Portal's own folders.
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator) || !f.isFile) {
            throw FileNotFoundException(uri.toString())
        }
        return f to name
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (f, _) = resolve(uri)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (f, name) = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row.toTypedArray()) }
    }

    override fun getType(uri: Uri): String = try {
        ShelfStore.guessMime(resolve(uri).second)
    } catch (_: Exception) {
        "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
