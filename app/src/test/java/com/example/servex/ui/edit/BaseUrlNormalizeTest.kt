package com.example.servex.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlNormalizeTest {
    @Test fun keepsBareHostAndPort() {
        assertEquals("http://192.168.0.10:8765", normalizeBaseUrl("192.168.0.10:8765"))
    }

    @Test fun addsSchemeAndDefaultPortToBareHost() {
        assertEquals("http://100.64.0.1:8765", normalizeBaseUrl("100.64.0.1"))
    }

    @Test fun addsSchemeToHostname() {
        assertEquals("http://web-01.lan:8765", normalizeBaseUrl("web-01.lan"))
    }

    @Test fun keepsHttpsWithoutAddingPort() {
        assertEquals("https://host.ts.net", normalizeBaseUrl("https://host.ts.net"))
    }

    @Test fun keepsExplicitHttpPortAndStripsTrailingSlash() {
        assertEquals("http://h:9000", normalizeBaseUrl("http://h:9000/"))
    }

    @Test fun rejectsUnsupportedScheme() {
        assertNull(normalizeBaseUrl("ftp://x"))
    }

    @Test fun rejectsBlank() {
        assertNull(normalizeBaseUrl("   "))
    }

    @Test fun validateServerAcceptsBareAddress() {
        assertNull(validateServer("web", "192.168.0.10:8765"))
        assertEquals("err_url", validateServer("web", "ftp://x"))
    }
}
