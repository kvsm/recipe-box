package app.recipebox.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * Keeps a copy of each recipe's photo on the phone. Image links from recipe
 * sites, and especially Instagram and TikTok, expire or move, so a linked
 * photo would eventually go blank. Photos are scaled down to card size.
 */
class RecipePhotos(private val context: Context, private val http: OkHttpClient) {
    private val dir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    /** Downloads the first candidate that works and returns its local URI, or null. */
    suspend fun download(candidates: List<String?>): String? = withContext(Dispatchers.IO) {
        for (url in candidates.filterNotNull().map { it.trim() }.filter { it.startsWith("http") }.distinct()) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val bytes = http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                } ?: continue
                save(bytes)?.let { return@withContext it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next candidate.
            }
        }
        null
    }

    /** Copies a photo the user picked or took (any content:// or file:// URI) and returns its local URI. */
    suspend fun copyFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        save(bytes)
    }

    /** Deletes a photo if it's one of ours. Links and other files are left alone. */
    fun delete(uri: String?) {
        val path = uri?.let { runCatching { Uri.parse(it).path }.getOrNull() } ?: return
        val file = File(path)
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
    }

    /** A temporary file for the camera to write into. */
    fun newCameraFile(): File = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    private fun save(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_SIZE || bounds.outHeight < MIN_SIZE) return null // tracking pixels, icons
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Uri.fromFile(file).toString()
    }

    companion object {
        private const val MAX_SIZE = 1600
        private const val MIN_SIZE = 200
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        private val youTubeId = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|youtu\.be/)([\w-]{11})""")

        /** YouTube's own thumbnails, best first, for a YouTube or Shorts link. */
        fun youTubeThumbnails(url: String): List<String> {
            val id = youTubeId.find(url)?.groupValues?.get(1) ?: return emptyList()
            return listOf("https://i.ytimg.com/vi/$id/maxresdefault.jpg", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }
    }
}
