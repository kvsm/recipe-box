package app.recipebox.importer

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text in a screenshot on the phone itself (Google ML Kit, bundled
 * model: free, offline, nothing leaves the device). Used when there's no Claude
 * key, or Claude can't be reached.
 */
object OnDeviceText {
    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw ImportException("Couldn't read that image.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    // One line per recognised line, top to bottom, so headings stay on their own lines.
                    .addOnSuccessListener { result ->
                        cont.resume(result.textBlocks.flatMap { it.lines }.joinToString("\n") { it.text })
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }
}
