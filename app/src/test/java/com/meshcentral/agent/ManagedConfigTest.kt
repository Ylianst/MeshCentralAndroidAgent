package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedConfigTest {
    private val link = "mc://mesh.example.com,certificateHash,deviceGroup"

    @Test
    fun emptyConfigManagesNothing() {
        val config = parseManagedConfig(emptyMap())
        assertNull(config.serverLink)
        assertNull(config.autoConnect)
        assertNull(config.autoConsent)
        assertFalse(config.lockServer)
    }

    @Test
    fun readsAllValues() {
        val config = parseManagedConfig(mapOf(
            MANAGED_SERVER_LINK to " $link ",
            MANAGED_AUTO_CONNECT to true,
            MANAGED_AUTO_CONSENT to false,
            MANAGED_LOCK_SERVER to true
        ))
        assertEquals(link, config.serverLink)
        assertEquals(true, config.autoConnect)
        assertEquals(false, config.autoConsent)
        assertTrue(config.lockServer)
    }

    @Test
    fun ignoresInvalidOrEmptyServerLink() {
        assertNull(parseManagedConfig(mapOf(MANAGED_SERVER_LINK to "https://mesh.example.com")).serverLink)
        assertNull(parseManagedConfig(mapOf(MANAGED_SERVER_LINK to "")).serverLink)
        assertNull(parseManagedConfig(mapOf(MANAGED_SERVER_LINK to 42)).serverLink)
    }

    @Test
    fun ignoresValuesOfWrongType() {
        val config = parseManagedConfig(mapOf(MANAGED_AUTO_CONNECT to "true", MANAGED_LOCK_SERVER to "yes"))
        assertNull(config.autoConnect)
        assertFalse(config.lockServer)
    }
}
