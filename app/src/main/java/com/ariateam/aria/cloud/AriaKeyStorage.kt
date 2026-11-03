package com.ariateam.aria.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AriaKeyStorage {
    fun saveApiKey(key: String): Boolean
    fun getApiKey(): String?
    fun clearApiKey(): Boolean
    fun hasApiKey(): Boolean
    fun getMaskedApiKey(): String?
}

class AndroidKeyStoreAriaKeyStorage(
    private val context: Context,
) : AriaKeyStorage {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveApiKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return clearApiKey()
        }

        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)

            preferences.edit()
                .putString(KEY_IV, ivBase64)
                .putString(KEY_CIPHERTEXT, ctBase64)
                .commit()
        } catch (t: Throwable) {
            safeLogE(TAG, "Failed to encrypt and save API key", t)
            false
        }
    }

    override fun getApiKey(): String? {
        val ivBase64 = preferences.getString(KEY_IV, null) ?: return null
        val ctBase64 = preferences.getString(KEY_CIPHERTEXT, null) ?: return null

        return try {
            val secretKey = getKey() ?: return null
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ct = Base64.decode(ctBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(ct)
            String(plainBytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            safeLogE(TAG, "Failed to decrypt API key", t)
            null
        }
    }

    override fun clearApiKey(): Boolean {
        return try {
            preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
            val keyStore = loadKeyStore()
            if (keyStore?.containsAlias(KEY_ALIAS) == true) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            true
        } catch (t: Throwable) {
            safeLogE(TAG, "Failed to clear API key", t)
            preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
        }
    }

    override fun hasApiKey(): Boolean {
        return preferences.contains(KEY_IV) && preferences.contains(KEY_CIPHERTEXT)
    }

    override fun getMaskedApiKey(): String? {
        val key = getApiKey() ?: return null
        return mask(key)
    }

    private fun loadKeyStore(): KeyStore? {
        return try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
            ?: throw IllegalStateException("AndroidKeyStore unavailable")

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getKey(): SecretKey? {
        val keyStore = loadKeyStore() ?: return null
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    companion object {
        const val PREFS_NAME = "aria_cloud_secure_prefs"
        private const val KEY_ALIAS = "aria_gemini_api_key_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_IV = "secure_iv"
        private const val KEY_CIPHERTEXT = "secure_ct"
        private const val TAG = "AriaKeyStorage"

        fun mask(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.length >= 10) {
                "${trimmed.take(4)}...${trimmed.takeLast(4)}"
            } else {
                "••••••••"
            }
        }

        private fun safeLogE(tag: String, msg: String, t: Throwable?) {
            try {
                Log.e(tag, msg, t)
            } catch (_: Throwable) {}
        }
    }
}

class InMemoryAriaKeyStorage(
    private var key: String? = null,
) : AriaKeyStorage {
    override fun saveApiKey(key: String): Boolean {
        val trimmed = key.trim()
        this.key = trimmed.ifEmpty { null }
        return true
    }

    override fun getApiKey(): String? = key

    override fun clearApiKey(): Boolean {
        key = null
        return true
    }

    override fun hasApiKey(): Boolean = !key.isNullOrBlank()

    override fun getMaskedApiKey(): String? = key?.let { AndroidKeyStoreAriaKeyStorage.mask(it) }
}
