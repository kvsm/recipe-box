package app.recipebox.importer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.recipebox.RecipeBoxApp
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface ImportRequest {
    data class Url(val url: String) : ImportRequest
    data class TextFile(val path: String) : ImportRequest
    data class ImageFile(val path: String) : ImportRequest

    fun toData(): Data = when (this) {
        is Url -> workDataOf(KEY_KIND to "url", KEY_VALUE to url)
        is TextFile -> workDataOf(KEY_KIND to "text", KEY_VALUE to path)
        is ImageFile -> workDataOf(KEY_KIND to "image", KEY_VALUE to path)
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_VALUE = "value"

        fun from(data: Data): ImportRequest? {
            val value = data.getString(KEY_VALUE) ?: return null
            return when (data.getString(KEY_KIND)) {
                "url" -> Url(value)
                "text" -> TextFile(value)
                "image" -> ImageFile(value)
                else -> null
            }
        }
    }
}

/** Runs one import in the background so it survives the share sheet closing. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = ImportRequest.from(inputData) ?: return Result.failure(errorData("That import request was malformed."))
        val importer = (applicationContext as RecipeBoxApp).container.importer
        return try {
            Result.success(workDataOf(KEY_RECIPE_ID to importer.import(request, canRetry = runAttemptCount < MAX_ATTEMPTS)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(errorData(e.message))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(errorData("Couldn't reach the internet. Check your connection and try again."))
        } catch (e: Exception) {
            Result.failure(errorData("Import failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun errorData(message: String?) = workDataOf(KEY_ERROR to (message ?: "Import failed."))

    companion object {
        const val KEY_RECIPE_ID = "recipe_id"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3
    }
}

object ImportScheduler {
    const val TAG = "import"
    private const val LABEL_PREFIX = "label:"

    fun importUrl(context: Context, url: String) {
        val host = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull()
        enqueue(context, ImportRequest.Url(url), host ?: "that link")
    }

    fun importText(context: Context, text: String) {
        val file = newImportFile(context, "txt").apply { writeText(text) }
        enqueue(context, ImportRequest.TextFile(file.path), "shared text")
    }

    fun importImage(context: Context, path: String) {
        enqueue(context, ImportRequest.ImageFile(path), "your screenshot")
    }

    /** Files for pending imports live in app storage so the worker can read them later. */
    fun newImportFile(context: Context, extension: String): File =
        File(context.filesDir, "imports").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.$extension") }

    fun labelOf(info: WorkInfo): String =
        info.tags.firstOrNull { it.startsWith(LABEL_PREFIX) }?.removePrefix(LABEL_PREFIX) ?: "a recipe"

    private fun enqueue(context: Context, request: ImportRequest, label: String) {
        val work = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(request.toData())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(LABEL_PREFIX + label.take(80))
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
