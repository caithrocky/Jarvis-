package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores personal identity details, saved website/app login credentials, and runtime API overrides
 * encrypted at rest using an Android Keystore AES-256-GCM key.
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

    fun getSignupProfile(): SignupProfile {
        return SignupProfile(
            fullName = getDecrypted(KEY_SIGNUP_FULL_NAME).orEmpty(),
            username = getDecrypted(KEY_SIGNUP_USERNAME).orEmpty(),
            email = getDecrypted(KEY_SIGNUP_EMAIL).orEmpty(),
            phoneNumber = getDecrypted(KEY_SIGNUP_PHONE).orEmpty(),
            dateOfBirth = getDecrypted(KEY_SIGNUP_DOB).orEmpty(),
            country = getDecrypted(KEY_SIGNUP_COUNTRY).orEmpty(),
            addressLine = getDecrypted(KEY_SIGNUP_ADDRESS).orEmpty(),
            city = getDecrypted(KEY_SIGNUP_CITY).orEmpty(),
            postalCode = getDecrypted(KEY_SIGNUP_POSTAL).orEmpty(),
            defaultPassword = getDecrypted(KEY_SIGNUP_DEFAULT_PASSWORD).orEmpty(),
            allowAssistedFill = getDecrypted(KEY_SIGNUP_ALLOW_AUTOFILL) == "true"
        )
    }

    fun saveSignupProfile(profile: SignupProfile) {
        putEncrypted(KEY_SIGNUP_FULL_NAME, profile.fullName.trim())
        putEncrypted(KEY_SIGNUP_USERNAME, profile.username.trim())
        putEncrypted(KEY_SIGNUP_EMAIL, profile.email.trim())
        putEncrypted(KEY_SIGNUP_PHONE, profile.phoneNumber.trim())
        putEncrypted(KEY_SIGNUP_DOB, profile.dateOfBirth.trim())
        putEncrypted(KEY_SIGNUP_COUNTRY, profile.country.trim())
        putEncrypted(KEY_SIGNUP_ADDRESS, profile.addressLine.trim())
        putEncrypted(KEY_SIGNUP_CITY, profile.city.trim())
        putEncrypted(KEY_SIGNUP_POSTAL, profile.postalCode.trim())
        putEncrypted(KEY_SIGNUP_DEFAULT_PASSWORD, profile.defaultPassword)
        putEncrypted(KEY_SIGNUP_ALLOW_AUTOFILL, if (profile.allowAssistedFill) "true" else "false")
    }

    fun getSavedCredentials(): List<SavedCredentialEntry> {
        val rawJson = getDecrypted(KEY_SAVED_CREDENTIALS_JSON) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val service = obj.optString("serviceName").trim()
                    if (service.isBlank()) continue
                    add(
                        SavedCredentialEntry(
                            id = id,
                            serviceName = service,
                            websiteOrApp = obj.optString("websiteOrApp").trim(),
                            usernameOrEmail = obj.optString("usernameOrEmail").trim(),
                            password = obj.optString("password"),
                            notes = obj.optString("notes").trim()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsertSavedCredential(entry: SavedCredentialEntry): List<SavedCredentialEntry> {
        val current = getSavedCredentials().toMutableList()
        val idx = current.indexOfFirst { it.id == entry.id }
        val normalized = entry.copy(
            id = entry.id.ifBlank { UUID.randomUUID().toString() },
            serviceName = entry.serviceName.trim(),
            websiteOrApp = entry.websiteOrApp.trim(),
            usernameOrEmail = entry.usernameOrEmail.trim(),
            notes = entry.notes.trim()
        )
        if (idx >= 0) {
            current[idx] = normalized
        } else {
            current.add(0, normalized)
        }
        persistSavedCredentials(current)
        return current
    }

    fun deleteSavedCredential(id: String): List<SavedCredentialEntry> {
        val updated = getSavedCredentials().filterNot { it.id == id }
        persistSavedCredentials(updated)
        return updated
    }

    fun findCredentialForTarget(targetOrPackage: String, screenContext: String = ""): SavedCredentialEntry? {
        val entries = getSavedCredentials()
        if (entries.isEmpty()) return null
        val q = targetOrPackage.trim().lowercase(Locale.US)
        val screenLower = screenContext.lowercase(Locale.US)

        if (q.isNotBlank() && q != "autofill") {
            entries.firstOrNull {
                it.serviceName.lowercase(Locale.US) == q ||
                    it.websiteOrApp.lowercase(Locale.US).contains(q) ||
                    q.contains(it.serviceName.lowercase(Locale.US))
            }?.let { return it }
        }

        if (screenLower.isNotBlank()) {
            entries.firstOrNull { entry ->
                val s = entry.serviceName.lowercase(Locale.US)
                val w = entry.websiteOrApp.lowercase(Locale.US)
                    .removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore("/")
                (s.length >= 2 && screenLower.contains(s)) || (w.length >= 3 && screenLower.contains(w))
            }?.let { return it }
        }
        return null
    }

    private fun persistSavedCredentials(entries: List<SavedCredentialEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("serviceName", e.serviceName)
                    put("websiteOrApp", e.websiteOrApp)
                    put("usernameOrEmail", e.usernameOrEmail)
                    put("password", e.password)
                    put("notes", e.notes)
                }
            )
        }
        putEncrypted(KEY_SAVED_CREDENTIALS_JSON, arr.toString())
    }

    fun generateStrongPassword(length: Int = 16): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#$%&*_-+"
        val all = upper + lower + digits + symbols
        val rng = java.security.SecureRandom()
        val chars = mutableListOf(
            upper[rng.nextInt(upper.length)],
            lower[rng.nextInt(lower.length)],
            digits[rng.nextInt(digits.length)],
            symbols[rng.nextInt(symbols.length)]
        )
        while (chars.size < length.coerceAtLeast(12)) {
            chars.add(all[rng.nextInt(all.length)])
        }
        chars.shuffle(rng)
        return chars.joinToString("")
    }

    fun getOwnerVoiceProfile(): OwnerVoiceProfile? {
        val raw = getDecrypted(KEY_OWNER_VOICE_PROFILE_JSON) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val anchorArr = obj.optJSONArray("anchorBands") ?: return null
            val adaptiveArr = obj.optJSONArray("adaptiveBands") ?: anchorArr
            val anchorBands = DoubleArray(anchorArr.length()) { i -> anchorArr.optDouble(i, 0.0) }
            val adaptiveBands = DoubleArray(adaptiveArr.length()) { i -> adaptiveArr.optDouble(i, 0.0) }
            if (anchorBands.isEmpty()) return null
            OwnerVoiceProfile(
                anchorBands = anchorBands,
                adaptiveBands = adaptiveBands,
                baselinePitchHz = obj.optDouble("baselinePitchHz", 125.0),
                adaptivePitchHz = obj.optDouble("adaptivePitchHz", 125.0),
                baselineZcr = obj.optDouble("baselineZcr", 0.08),
                baselineSpectralTilt = obj.optDouble("baselineSpectralTilt", 0.5),
                sampleCount = obj.optInt("sampleCount", 1),
                fluAdaptedCount = obj.optInt("fluAdaptedCount", 0),
                lastUpdatedMs = obj.optLong("lastUpdatedMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun saveOwnerVoiceProfile(profile: OwnerVoiceProfile) {
        val obj = JSONObject().apply {
            put("anchorBands", JSONArray().apply { profile.anchorBands.forEach { put(it) } })
            put("adaptiveBands", JSONArray().apply { profile.adaptiveBands.forEach { put(it) } })
            put("baselinePitchHz", profile.baselinePitchHz)
            put("adaptivePitchHz", profile.adaptivePitchHz)
            put("baselineZcr", profile.baselineZcr)
            put("baselineSpectralTilt", profile.baselineSpectralTilt)
            put("sampleCount", profile.sampleCount)
            put("fluAdaptedCount", profile.fluAdaptedCount)
            put("lastUpdatedMs", profile.lastUpdatedMs)
        }
        putEncrypted(KEY_OWNER_VOICE_PROFILE_JSON, obj.toString())
    }

    fun clearOwnerVoiceProfile() {
        remove(KEY_OWNER_VOICE_PROFILE_JSON)
    }

    fun saveDeviceSafeWord(safeWord: String) {
        val cleaned = safeWord.trim()
        if (cleaned.isBlank()) {
            remove(KEY_DEVICE_SAFE_WORD)
        } else {
            putEncrypted(KEY_DEVICE_SAFE_WORD, cleaned)
        }
    }

    fun getDeviceSafeWord(): String = getDecrypted(KEY_DEVICE_SAFE_WORD).orEmpty()

    fun hasDeviceSafeWord(): Boolean = getDeviceSafeWord().isNotBlank()

    fun verifySafeWord(spokenText: String): Boolean {
        val expected = normalizeSafeWordPhrase(getDeviceSafeWord())
        if (expected.isBlank()) return false
        val spokenNorm = normalizeSafeWordPhrase(spokenText)
        if (spokenNorm == expected) return true
        val strippedLead = spokenNorm
            .removePrefix("my safe word is ")
            .removePrefix("the safe word is ")
            .removePrefix("safe word is ")
            .removePrefix("safe word ")
            .removePrefix("it is ")
            .removePrefix("its ")
            .removePrefix("it's ")
            .trim()
        return strippedLead == expected
    }

    private fun normalizeSafeWordPhrase(raw: String): String =
        raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun saveDeviceUnlockPattern(dots: List<Int>) {
        val valid = dots.filter { it in 1..9 }.distinct()
        if (valid.size < 4) {
            remove(KEY_DEVICE_UNLOCK_PATTERN)
        } else {
            putEncrypted(KEY_DEVICE_UNLOCK_PATTERN, valid.joinToString(","))
        }
    }

    fun getDeviceUnlockPattern(): List<Int> {
        val raw = getDecrypted(KEY_DEVICE_UNLOCK_PATTERN).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..9 }
            .distinct()
    }

    fun hasDeviceUnlockPattern(): Boolean = getDeviceUnlockPattern().size >= 4

    fun clearDeviceUnlockCredentials() {
        remove(KEY_DEVICE_SAFE_WORD)
        remove(KEY_DEVICE_UNLOCK_PATTERN)
    }

    data class OwnerVoiceProfile(
        val anchorBands: DoubleArray,
        val adaptiveBands: DoubleArray,
        val baselinePitchHz: Double,
        val adaptivePitchHz: Double,
        val baselineZcr: Double,
        val baselineSpectralTilt: Double,
        val sampleCount: Int,
        val fluAdaptedCount: Int = 0,
        val lastUpdatedMs: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OwnerVoiceProfile) return false
            return anchorBands.contentEquals(other.anchorBands) &&
                adaptiveBands.contentEquals(other.adaptiveBands) &&
                baselinePitchHz == other.baselinePitchHz &&
                adaptivePitchHz == other.adaptivePitchHz &&
                sampleCount == other.sampleCount
        }

        override fun hashCode(): Int {
            var result = anchorBands.contentHashCode()
            result = 31 * result + adaptiveBands.contentHashCode()
            result = 31 * result + sampleCount
            return result
        }
    }

    data class SignupProfile(
        val fullName: String = "",
        val username: String = "",
        val email: String = "",
        val phoneNumber: String = "",
        val dateOfBirth: String = "",
        val country: String = "",
        val addressLine: String = "",
        val city: String = "",
        val postalCode: String = "",
        val defaultPassword: String = "",
        val allowAssistedFill: Boolean = false
    )

    data class SavedCredentialEntry(
        val id: String = UUID.randomUUID().toString(),
        val serviceName: String,
        val websiteOrApp: String = "",
        val usernameOrEmail: String,
        val password: String,
        val notes: String = ""
    )

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

        const val KEY_SIGNUP_FULL_NAME = "signup_full_name"
        const val KEY_SIGNUP_USERNAME = "signup_username"
        const val KEY_SIGNUP_EMAIL = "signup_email"
        const val KEY_SIGNUP_PHONE = "signup_phone"
        const val KEY_SIGNUP_DOB = "signup_dob"
        const val KEY_SIGNUP_COUNTRY = "signup_country"
        const val KEY_SIGNUP_ADDRESS = "signup_address"
        const val KEY_SIGNUP_CITY = "signup_city"
        const val KEY_SIGNUP_POSTAL = "signup_postal"
        const val KEY_SIGNUP_DEFAULT_PASSWORD = "signup_default_password"
        const val KEY_SIGNUP_ALLOW_AUTOFILL = "signup_allow_autofill"
        const val KEY_SAVED_CREDENTIALS_JSON = "saved_credentials_json"
        const val KEY_NVIDIA_NIM_API_OVERRIDE = "nvidia_nim_api_override"
        const val KEY_OWNER_VOICE_PROFILE_JSON = "owner_voice_profile_json"
        const val KEY_DEVICE_SAFE_WORD = "device_safe_word"
        const val KEY_DEVICE_UNLOCK_PATTERN = "device_unlock_pattern"
    }
}
