package app.recipebox.groceries

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import app.recipebox.settings.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends shopping items somewhere useful: straight into OurGroceries when it's
 * connected, otherwise onto the clipboard with OurGroceries opened.
 */
class GroceryRouter(
    private val context: Context,
    private val ourGroceries: OurGroceriesClient,
    private val secrets: SecretStore,
) {
    sealed interface Outcome {
        data class Added(val count: Int, val listName: String) : Outcome
        /** [reason] is set when a direct add was tried and failed. */
        data class Copied(val count: Int, val openedApp: Boolean, val reason: String?) : Outcome
    }

    var listName: String
        get() = secrets.get(SecretStore.OG_LIST_NAME)?.ifBlank { null } ?: DEFAULT_LIST
        set(value) = secrets.put(SecretStore.OG_LIST_NAME, value.trim().ifEmpty { null })

    suspend fun send(items: List<GroceryItem>): Outcome {
        var failure: String? = null
        if (ourGroceries.isConnected()) {
            try {
                return Outcome.Added(ourGroceries.addItems(items, listName), listName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message
            }
        }
        return withContext(Dispatchers.Main) {
            copyToClipboard(items)
            Outcome.Copied(items.size, openOurGroceries(), failure)
        }
    }

    /** A plain "share these items" chooser for any other list app. */
    fun shareIntent(items: List<GroceryItem>): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, items.joinToString("\n"))
            },
            "Send shopping list",
        )

    private fun copyToClipboard(items: List<GroceryItem>) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Shopping list", items.joinToString("\n")))
    }

    private fun openOurGroceries(): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(OUR_GROCERIES_PACKAGE) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        const val DEFAULT_LIST = "Shopping"
        // Check this against the Play Store listing if "open OurGroceries" doesn't fire.
        private const val OUR_GROCERIES_PACKAGE = "com.headcode.ourgroceries"
    }
}
