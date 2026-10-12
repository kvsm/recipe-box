package app.recipebox.settings

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.Base64

/**
 * Small encrypted key/value store for secrets (Claude API key, OurGroceries
 * token). Values are AES-256-GCM encrypted with a key held in Android Keystore.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "tink_keyset", "tink_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://recipebox_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    @Synchronized
    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            String(aead.decrypt(Base64.getDecoder().decode(stored), key.toByteArray()), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray())
        prefs.edit().putString(key, Base64.getEncoder().encodeToString(encrypted)).apply()
    }

    companion object {
        const val CLAUDE_API_KEY = "claude_api_key"
        const val OG_ACCESS_TOKEN = "og_access_token"
        const val OG_TOKEN_EXPIRES_AT = "og_token_expires_at"
        const val OG_CLIENT_ID = "og_client_id"
        const val OG_PKCE_VERIFIER = "og_pkce_verifier"
        const val OG_STATE = "og_state"
        const val OG_LIST_NAME = "og_list_name"
    }
}
