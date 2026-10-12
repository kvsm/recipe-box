package app.recipebox.groceries

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.recipebox.RecipeBoxApp
import app.recipebox.ui.MainActivity
import kotlinx.coroutines.launch

/** Catches the OurGroceries sign-in redirect, finishes sign-in, and hops back into the app. */
class OAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val client = (application as RecipeBoxApp).container.ourGroceries
        lifecycleScope.launch {
            val message = if (data == null) {
                "Nothing came back from OurGroceries."
            } else {
                runCatching { client.completeSignIn(data) }
                    .fold({ "OurGroceries connected" }, { it.message ?: "OurGroceries sign-in failed." })
            }
            Toast.makeText(this@OAuthRedirectActivity, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@OAuthRedirectActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
