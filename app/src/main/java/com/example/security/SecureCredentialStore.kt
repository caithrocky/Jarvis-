package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores runtime-obtained session tokens (e.g., Deriv OAuth tokens from redirect,
 * Supabase access token) encrypted at rest using an Android Keystore AES-256-GCM key.
 */
class SecureCredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateAesKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }.getOrNull()

    fun putEncrypted(key: String, plainValue: String) {
        val secretKey = getOrCreateAesKey() ?: return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainValue.toByteArray(Charsets.UTF_8))
            val combined = ByteBuffer.allocate(4 + iv.size + cipherText.size)
                .putInt(iv.size)
                .put(iv)
                .put(cipherText)
                .array()
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(key, encoded).apply()
        }
    }

    fun getDecrypted(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        val secretKey = getOrCreateAesKey() ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(combined)
            val ivSize = buffer.int
            if (ivSize !in 12..32) return@runCatching null
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val cipherText = ByteArray(buffer.remaining())
            buffer.get(cipherText)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_keystore_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_vault_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_DERIV_OAUTH_TOKEN = "deriv_oauth_token"
        const val KEY_DERIV_OAUTH_ACCOUNT = "deriv_oauth_account"
        const val KEY_DERIV_OAUTH_CURRENCY = "deriv_oauth_currency"
        const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
        const val KEY_SUPABASE_USER_EMAIL = "supabase_user_email"
    }
}
