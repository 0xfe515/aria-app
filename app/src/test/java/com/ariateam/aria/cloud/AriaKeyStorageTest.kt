package com.ariateam.aria.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaKeyStorageTest {

    @Test
    fun inMemoryStorageSavesAndRetrievesKey() {
        val storage = InMemoryAriaKeyStorage()
        assertFalse(storage.hasApiKey())
        assertNull(storage.getApiKey())
        assertNull(storage.getMaskedApiKey())

        storage.saveApiKey("AIzaSyTestKey12345678")
        assertTrue(storage.hasApiKey())
        assertEquals("AIzaSyTestKey12345678", storage.getApiKey())
        assertEquals("AIza...5678", storage.getMaskedApiKey())

        storage.clearApiKey()
        assertFalse(storage.hasApiKey())
        assertNull(storage.getApiKey())
        assertNull(storage.getMaskedApiKey())
    }

    @Test
    fun emptyOrWhitespaceKeyClearsStorage() {
        val storage = InMemoryAriaKeyStorage()
        storage.saveApiKey("AIzaSyValidKey")
        assertTrue(storage.hasApiKey())

        storage.saveApiKey("   ")
        assertFalse(storage.hasApiKey())
        assertNull(storage.getApiKey())
    }

    @Test
    fun maskingRulesNeverExposeFullKey() {
        val longKey = "AIzaSyDa-abcdefg1234567890"
        val maskedLong = AndroidKeyStoreAriaKeyStorage.mask(longKey)
        assertEquals("AIza...7890", maskedLong)
        assertFalse("Masked key must not contain middle of secret", maskedLong.contains("abcdefg"))

        val shortKey = "secret123"
        val maskedShort = AndroidKeyStoreAriaKeyStorage.mask(shortKey)
        assertEquals("••••••••", maskedShort)

        val emptyKey = ""
        assertEquals("", AndroidKeyStoreAriaKeyStorage.mask(emptyKey))
    }
}
