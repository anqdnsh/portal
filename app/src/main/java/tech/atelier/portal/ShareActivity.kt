package tech.atelier.portal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import tech.atelier.portal.core.Incoming
import tech.atelier.portal.core.ShelfStore
import tech.atelier.portal.host.PortalHost

/** "Send to Portal" from any app's share menu. Copies what was shared onto the shelf, then gets out of the way. */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShelfStore.init(this)
        val incoming = collect(intent)
        if (incoming.isEmpty()) {
            Toast.makeText(this, "Nothing Portal can keep", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Streams are already open, so the copy can finish after this screen closes.
        ShelfStore.keep(incoming) { made ->
            Toast.makeText(applicationContext, if (made.size == 1) "Kept in Portal" else "Kept ${made.size} items in Portal", Toast.LENGTH_SHORT).show()
            PortalHost.hub?.open()
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun collect(i: Intent): List<Incoming> {
        val out = mutableListOf<Incoming>()
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        uris.forEach { u -> ShelfStore.openUri(this, u)?.let(out::add) }
        if (uris.isEmpty()) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) out.add(Incoming(ShelfStore.textName(t), "text/plain", null, t))
        }
        return out
    }
}
