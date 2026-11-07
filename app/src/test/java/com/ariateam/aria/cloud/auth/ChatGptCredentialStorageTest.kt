package com.ariateam.aria.cloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatGptCredentialStorageTest {

    private lateinit var storage: InMemoryChatGptCredentialStorage

    @Before
    fun setUp() {
        storage = InMemoryChatGptCredentialStorage(
            initialHostId = "urn:uuid:11111111-2222-4333-8444-555555555555",
        )
    }

    @Test
    fun testSaveAndRetrieveCredentials() {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_123",
            subject = "sub_user_abc",
            email = "test@example.com",
            name = "Test User",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "secret_access_token_123",
            refreshToken = "secret_refresh_token_456",
            idToken = "secret_id_token_789",
            tokenType = "Bearer",
            expiresAtEpochSec = 1790003600L,
            scopes = setOf("openid", "chatgpt.tokens.use.direct", "resource.invoke"),
            savedAtIso = "2026-11-08T00:00:00Z",
        )

        assertTrue(storage.saveCredentials(creds, makeActive = true))
        assertEquals("oaiapp_123", storage.getActiveClientId())

        val all = storage.loadAllCredentials()
        assertEquals(1, all.size)
        val loaded = all["oaiapp_123"]
        assertNotNull(loaded)
        assertEquals("sub_user_abc", loaded?.subject)
        assertEquals("secret_access_token_123", loaded?.accessToken)
        assertTrue(loaded?.hasPlanUsagePermission == true)
    }

    @Test
    fun testClearTokensRetainsRegistrationMapping() {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_123",
            subject = "sub_user_abc",
            email = "test@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "secret_access_token_123",
            refreshToken = "secret_refresh_token_456",
            idToken = "secret_id_token_789",
            expiresAtEpochSec = 1790003600L,
            scopes = setOf("openid", "chatgpt.tokens.use.direct", "resource.invoke"),
        )
        storage.saveCredentials(creds, makeActive = true)

        storage.clearTokensForClient("oaiapp_123")

        val all = storage.loadAllCredentials()
        val afterLogout = all["oaiapp_123"]
        assertNotNull(afterLogout)
        assertEquals("sub_user_abc", afterLogout?.subject)
        assertEquals("test@example.com", afterLogout?.email)
        assertEquals("oaiapp_123", afterLogout?.clientId)
        assertNull(afterLogout?.accessToken)
        assertNull(afterLogout?.refreshToken)
        assertNull(afterLogout?.idToken)
        assertFalse(afterLogout?.hasActiveTokens == true)
        assertNull(storage.getActiveClientId())
    }

    @Test
    fun testRevokedTombstoneFailClosed() {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_tombstone",
            subject = "sub_tombstone",
            email = "tombstone@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "token_before_revoke",
            refreshToken = "refresh_before_revoke",
            idToken = "id_before_revoke",
            expiresAtEpochSec = 1790003600L,
            scopes = setOf("openid", "chatgpt.tokens.use.direct", "resource.invoke"),
        )
        storage.saveCredentials(creds, makeActive = true)
        assertEquals("oaiapp_tombstone", storage.getActiveClientId())

        // Mark revoked tombstone (simulate clear failure fallback)
        storage.markRevoked("oaiapp_tombstone")
        assertTrue(storage.isRevoked("oaiapp_tombstone"))
        assertNull(storage.getActiveClientId())

        // Even though stored accounts map had tokens, loadAllCredentials strips them
        val loaded = storage.loadAllCredentials()["oaiapp_tombstone"]
        assertNotNull(loaded)
        assertNull(loaded?.accessToken)
        assertNull(loaded?.refreshToken)
        assertNull(loaded?.idToken)
    }

    @Test
    fun testCredentialsToStringNeverExposesSecrets() {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_123",
            subject = "sub_user_abc",
            email = "test@example.com",
            extAgentHostId = "urn:uuid:test",
            accessToken = "super_secret_access_token_value",
            refreshToken = "super_secret_refresh_token_value",
            idToken = "super_secret_id_token_value",
        )

        val str = creds.toString()
        assertFalse(str.contains("super_secret_access_token_value"))
        assertFalse(str.contains("super_secret_refresh_token_value"))
        assertFalse(str.contains("super_secret_id_token_value"))
        assertTrue(str.contains("hasAccessToken=true"))
        assertTrue(str.contains("hasRefreshToken=true"))
    }

    @Test
    fun testAndroidKeyStoreStorageRevokeCommitFailureFallsBackToInMemoryDenySet() {
        val fakePrefs = FakeSharedPreferences(failCommit = true)
        val storage = AndroidKeyStoreChatGptCredentialStorage(
            sharedPreferencesOverride = fakePrefs,
        )

        assertFalse("markRevoked should return false when disk commit fails", storage.markRevoked("revoked_client_1"))
        assertTrue("isRevoked should immediately return true via in-memory deny set even when commit failed", storage.isRevoked("revoked_client_1"))
    }

    @Test
    fun testAndroidKeyStoreStorageGetExtAgentHostIdCommitFailureThrowsIllegalStateException() {
        val fakePrefs = FakeSharedPreferences(failCommit = true)
        val storage = AndroidKeyStoreChatGptCredentialStorage(
            sharedPreferencesOverride = fakePrefs,
        )

        try {
            storage.getExtAgentHostId()
            org.junit.Assert.fail("Expected IllegalStateException when persisting host ID fails")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Failed to commit extAgentHostId") == true)
        }
    }

    private class FakeSharedPreferences(
        var failCommit: Boolean = false,
    ) : android.content.SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()

        inner class FakeEditor : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                if (key != null) pending[key] = values?.toSet()
                return this
            }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }
            override fun clear(): android.content.SharedPreferences.Editor {
                removes.addAll(map.keys)
                return this
            }
            override fun commit(): Boolean {
                if (failCommit) return false
                for (r in removes) map.remove(r)
                map.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
