package com.ariateam.aria.cloud.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ChatGptCredentialStorage {
    fun loadAllCredentials(): Map<String, ChatGptStoredCredentials>
    fun getActiveClientId(): String?
    fun saveCredentials(credentials: ChatGptStoredCredentials, makeActive: Boolean = true): Boolean
    fun setActiveClientId(clientId: String?): Boolean
    fun clearTokensForClient(clientId: String): Boolean
    fun deleteAccount(clientId: String): Boolean
    fun getExtAgentHostId(): String
    fun markRevoked(clientId: String): Boolean
    fun isRevoked(clientId: String): Boolean
}

class AndroidKeyStoreChatGptCredentialStorage(
    private val context: Context? = null,
    private val prefsName: String = ChatGptAuthConstants.PREFS_NAME,
    private val keyAlias: String = ChatGptAuthConstants.KEYSTORE_ALIAS,
    sharedPreferencesOverride: android.content.SharedPreferences? = null,
) : ChatGptCredentialStorage {

    private val preferences = sharedPreferencesOverride
        ?: context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        ?: error("Either context or sharedPreferencesOverride must be provided")
    private val inMemoryRevokedClients = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun loadAllCredentials(): Map<String, ChatGptStoredCredentials> {
        val jsonStr = decryptPayload() ?: return emptyMap()
        return try {
            val root = JSONObject(jsonStr)
            val accountsObj = root.optJSONObject("accounts") ?: return emptyMap()
            val result = mutableMapOf<String, ChatGptStoredCredentials>()
            val keys = accountsObj.keys()
            while (keys.hasNext()) {
                val clientId = keys.next()
                val item = accountsObj.getJSONObject(clientId)
                val creds = parseStoredCredentials(item)
                if (isRevoked(clientId)) {
                    // Fail-closed: strip any lingering tokens for revoked tombstone
                    result[clientId] = creds.copy(
                        accessToken = null,
                        refreshToken = null,
                        idToken = null,
                        expiresAtEpochSec = 0L,
                        earliestRefreshAtEpochSec = null,
                    )
                } else {
                    result[clientId] = creds
                }
            }
            result
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    override fun getActiveClientId(): String? {
        val jsonStr = decryptPayload() ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val active = if (root.has("activeClientId")) root.optString("activeClientId", null) else null
            if (active != null && isRevoked(active)) null else active
        } catch (_: Throwable) {
            null
        }
    }

    override fun saveCredentials(credentials: ChatGptStoredCredentials, makeActive: Boolean): Boolean {
        return try {
            val all = loadAllCredentials().toMutableMap()
            all[credentials.clientId] = credentials
            val currentActive = if (makeActive) credentials.clientId else getActiveClientId()
            // Clear any old revocation tombstone on explicit fresh credentials save
            clearRevokedTombstone(credentials.clientId)
            persistAll(all, currentActive)
        } catch (_: Throwable) {
            false
        }
    }

    override fun setActiveClientId(clientId: String?): Boolean {
        return try {
            if (clientId != null && isRevoked(clientId)) return false
            val all = loadAllCredentials()
            persistAll(all, clientId)
        } catch (_: Throwable) {
            false
        }
    }

    override fun clearTokensForClient(clientId: String): Boolean {
        return try {
            val all = loadAllCredentials().toMutableMap()
            val existing = all[clientId]
            if (existing != null) {
                // Retain client + subject registration / host mappings on logout, clear tokens
                val cleared = existing.copy(
                    accessToken = null,
                    refreshToken = null,
                    idToken = null,
                    expiresAtEpochSec = 0L,
                    earliestRefreshAtEpochSec = null,
                )
                all[clientId] = cleared
            }
            val active = getActiveClientId()
            val newActive = if (active == clientId) null else active
            val persistedOk = persistAll(all, newActive)
            if (!persistedOk) {
                // Exceptional fail-closed fallback: mark durable tombstone
                markRevoked(clientId)
                return false
            }
            true
        } catch (_: Throwable) {
            markRevoked(clientId)
            false
        }
    }

    override fun deleteAccount(clientId: String): Boolean {
        return try {
            val all = loadAllCredentials().toMutableMap()
            all.remove(clientId)
            val active = getActiveClientId()
            val newActive = if (active == clientId) null else active
            markRevoked(clientId)
            persistAll(all, newActive)
        } catch (_: Throwable) {
            markRevoked(clientId)
            false
        }
    }

    override fun getExtAgentHostId(): String {
        val existing = preferences.getString(KEY_HOST_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newHostId = ChatGptCrypto.generateHostId()
        val committed = preferences.edit().putString(KEY_HOST_ID, newHostId).commit()
        if (!committed) {
            throw IllegalStateException("Failed to commit extAgentHostId to storage")
        }
        return newHostId
    }

    override fun markRevoked(clientId: String): Boolean {
        inMemoryRevokedClients.add(clientId)
        return try {
            val tombstones = preferences.getStringSet(KEY_REVOKED_TOMBSTONES, emptySet())?.toMutableSet() ?: mutableSetOf()
            tombstones.add(clientId)
            val committed = preferences.edit().putStringSet(KEY_REVOKED_TOMBSTONES, tombstones).commit()
            if (!committed) {
                // Disk commit failed! Fail-closed: invalidate master KeyStore entry
                invalidateKeyStoreAlias()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            // Ultimate fail-closed fallback: invalidate master KeyStore entry
            invalidateKeyStoreAlias()
            false
        }
    }

    override fun isRevoked(clientId: String): Boolean {
        if (inMemoryRevokedClients.contains(clientId)) return true
        return try {
            val tombstones = preferences.getStringSet(KEY_REVOKED_TOMBSTONES, emptySet()) ?: emptySet()
            tombstones.contains(clientId)
        } catch (_: Throwable) {
            false
        }
    }

    private fun clearRevokedTombstone(clientId: String) {
        inMemoryRevokedClients.remove(clientId)
        try {
            val tombstones = preferences.getStringSet(KEY_REVOKED_TOMBSTONES, emptySet())?.toMutableSet() ?: return
            if (tombstones.remove(clientId)) {
                preferences.edit().putStringSet(KEY_REVOKED_TOMBSTONES, tombstones).commit()
            }
        } catch (_: Throwable) {}
    }

    private fun invalidateKeyStoreAlias() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (_: Throwable) {}
    }

    private fun persistAll(all: Map<String, ChatGptStoredCredentials>, activeClientId: String?): Boolean {
        val root = JSONObject()
        val accountsObj = JSONObject()
        for ((cid, creds) in all) {
            accountsObj.put(cid, serializeStoredCredentials(creds))
        }
        root.put("accounts", accountsObj)
        if (activeClientId != null) {
            root.put("activeClientId", activeClientId)
        }
        return encryptAndSave(root.toString())
    }

    private fun encryptAndSave(plaintext: String): Boolean {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

            preferences.edit()
                .putString(KEY_IV, ivBase64)
                .putString(KEY_CIPHERTEXT, ctBase64)
                .commit()
        } catch (_: Throwable) {
            false
        }
    }

    private fun decryptPayload(): String? {
        val ivBase64 = preferences.getString(KEY_IV, null) ?: return null
        val ctBase64 = preferences.getString(KEY_CIPHERTEXT, null) ?: return null

        return try {
            val secretKey = getSecretKey() ?: return null
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ct = Base64.decode(ctBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(ct)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    private fun serializeStoredCredentials(creds: ChatGptStoredCredentials): JSONObject {
        val obj = JSONObject()
        obj.put("clientId", creds.clientId)
        obj.put("subject", creds.subject)
        obj.put("email", creds.email)
        obj.put("name", creds.name)
        obj.put("extAgentHostId", creds.extAgentHostId)
        obj.put("accessToken", creds.accessToken)
        obj.put("refreshToken", creds.refreshToken)
        obj.put("idToken", creds.idToken)
        obj.put("tokenType", creds.tokenType)
        obj.put("expiresAtEpochSec", creds.expiresAtEpochSec)
        if (creds.earliestRefreshAtEpochSec != null) {
            obj.put("earliestRefreshAtEpochSec", creds.earliestRefreshAtEpochSec)
        }
        val scopesArray = JSONArray()
        creds.scopes.forEach { scopesArray.put(it) }
        obj.put("scopes", scopesArray)
        obj.put("savedAtIso", creds.savedAtIso)
        return obj
    }

    private fun parseStoredCredentials(obj: JSONObject): ChatGptStoredCredentials {
        val scopesSet = mutableSetOf<String>()
        val scopesArray = obj.optJSONArray("scopes")
        if (scopesArray != null) {
            for (i in 0 until scopesArray.length()) {
                scopesSet.add(scopesArray.getString(i))
            }
        }

        return ChatGptStoredCredentials(
            clientId = obj.getString("clientId"),
            subject = obj.getString("subject"),
            email = if (obj.has("email")) obj.optString("email", null) else null,
            name = if (obj.has("name")) obj.optString("name", null) else null,
            extAgentHostId = obj.optString("extAgentHostId", ""),
            accessToken = if (obj.has("accessToken")) obj.optString("accessToken", null) else null,
            refreshToken = if (obj.has("refreshToken")) obj.optString("refreshToken", null) else null,
            idToken = if (obj.has("idToken")) obj.optString("idToken", null) else null,
            tokenType = obj.optString("tokenType", "Bearer"),
            expiresAtEpochSec = obj.optLong("expiresAtEpochSec", 0L),
            earliestRefreshAtEpochSec = if (obj.has("earliestRefreshAtEpochSec")) obj.optLong("earliestRefreshAtEpochSec") else null,
            scopes = scopesSet,
            savedAtIso = obj.optString("savedAtIso", ""),
        )
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_IV = "auth_iv"
        private const val KEY_CIPHERTEXT = "auth_ct"
        private const val KEY_HOST_ID = "ext_agent_host_id"
        private const val KEY_REVOKED_TOMBSTONES = "revoked_client_tombstones"
    }
}

class InMemoryChatGptCredentialStorage(
    initialHostId: String = "urn:uuid:00000000-0000-4000-8000-000000000001",
) : ChatGptCredentialStorage {

    private val accounts = mutableMapOf<String, ChatGptStoredCredentials>()
    private val revokedTombstones = mutableSetOf<String>()
    private var activeClientId: String? = null
    private var hostId: String = initialHostId

    var failClearTokens: Boolean = false
    var failSaveCredentials: Boolean = false

    @Synchronized
    override fun loadAllCredentials(): Map<String, ChatGptStoredCredentials> {
        val result = mutableMapOf<String, ChatGptStoredCredentials>()
        for ((cid, creds) in accounts) {
            if (revokedTombstones.contains(cid)) {
                result[cid] = creds.copy(
                    accessToken = null,
                    refreshToken = null,
                    idToken = null,
                    expiresAtEpochSec = 0L,
                    earliestRefreshAtEpochSec = null,
                )
            } else {
                result[cid] = creds
            }
        }
        return result
    }

    @Synchronized
    override fun getActiveClientId(): String? {
        val active = activeClientId
        return if (active != null && revokedTombstones.contains(active)) null else active
    }

    @Synchronized
    override fun saveCredentials(credentials: ChatGptStoredCredentials, makeActive: Boolean): Boolean {
        if (failSaveCredentials) return false
        revokedTombstones.remove(credentials.clientId)
        accounts[credentials.clientId] = credentials
        if (makeActive) {
            activeClientId = credentials.clientId
        }
        return true
    }

    @Synchronized
    override fun setActiveClientId(clientId: String?): Boolean {
        if (clientId != null && revokedTombstones.contains(clientId)) return false
        activeClientId = clientId
        return true
    }

    @Synchronized
    override fun clearTokensForClient(clientId: String): Boolean {
        if (failClearTokens) {
            markRevoked(clientId)
            return false
        }
        val existing = accounts[clientId]
        if (existing != null) {
            accounts[clientId] = existing.copy(
                accessToken = null,
                refreshToken = null,
                idToken = null,
                expiresAtEpochSec = 0L,
                earliestRefreshAtEpochSec = null,
            )
        }
        if (activeClientId == clientId) {
            activeClientId = null
        }
        return true
    }

    @Synchronized
    override fun deleteAccount(clientId: String): Boolean {
        accounts.remove(clientId)
        markRevoked(clientId)
        if (activeClientId == clientId) {
            activeClientId = null
        }
        return true
    }

    @Synchronized
    override fun getExtAgentHostId(): String {
        return hostId
    }

    @Synchronized
    override fun markRevoked(clientId: String): Boolean {
        revokedTombstones.add(clientId)
        if (activeClientId == clientId) {
            activeClientId = null
        }
        return true
    }

    @Synchronized
    override fun isRevoked(clientId: String): Boolean {
        return revokedTombstones.contains(clientId)
    }
}
