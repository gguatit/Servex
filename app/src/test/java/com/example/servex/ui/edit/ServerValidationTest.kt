package com.example.servex.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerValidationTest {
    @Test fun validHttp() = assertNull(validateServer("web", "http://10.0.0.5:8765"))
    @Test fun validHttps() = assertNull(validateServer("web", "https://srv.local"))
    @Test fun blankNameRejected() = assertEquals("err_name", validateServer("", "http://x"))
    @Test fun blankUrlRejected() = assertEquals("err_url", validateServer("web", ""))
    @Test fun nonHttpSchemeRejected() = assertEquals("err_url", validateServer("web", "ftp://x"))
}
