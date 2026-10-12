package app.recipebox.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.recipebox.importer.ImportScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Save to Recipe Box" share target. Queues an import and gets out of the
 * way; the actual work happens in ImportWorker, so the user can go straight
 * back to Chrome or Instagram.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val message = runCatching { handle(intent) }.getOrElse { "Couldn't save that: ${it.message}" }
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun handle(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return "Nothing to save"

        if (intent.type.orEmpty().startsWith("image/")) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return "No image found"
            val file = withContext(Dispatchers.IO) {
                val target = ImportScheduler.newImportFile(this@ShareReceiverActivity, "img")
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("couldn't open the image")
                target
            }
            ImportScheduler.importImage(this, file.path)
            return "Reading your screenshot…"
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return "Nothing to save"

        // Browsers and Instagram share a link (sometimes with a title). A long caption
        // with a link tucked in is treated as text, since the caption is the recipe.
        val url = URL.find(text)?.value
        return if (url != null && text.length - url.length < 200) {
            ImportScheduler.importUrl(this, url)
            "Saving to your tin…"
        } else {
            ImportScheduler.importText(this, text)
            "Reading that recipe…"
        }
    }

    private companion object {
        val URL = Regex("""https?://\S+""")
    }
}
