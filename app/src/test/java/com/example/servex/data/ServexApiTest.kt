package com.example.servex.data

import com.example.servex.domain.model.Server
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServexApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServexApi

    @Before fun setUp() { server = MockWebServer().also { it.start() }; api = ServexApi(HttpClients.shared) }
    @After fun tearDown() { server.shutdown() }

    private fun target() = Server(id = "1", name = "t", baseUrl = server.url("/").toString().trimEnd('/'))

    @Test fun metrics_parsesSnakeCase() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"ts":5,"host":"web","os":"Ubuntu","uptime_s":10,"cpu":{"usage_pct":37.2,"cores":8},
               "memory":{"total":100,"used":40,"available":60,"pct":40.0},"disks":[],"network":[]}"""))
        val m = api.metrics(target(), "tok")
        assertEquals(37.2, m.cpu.usagePct, 0.001)
        assertEquals("web", m.host)
    }

    @Test fun metrics_ignoresUnknownKeys() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"ts":5,"host":"web","os":"u","uptime_s":1,"cpu":{"usage_pct":1.0,"cores":1,"extra":9},
               "memory":{"total":1,"used":1,"available":0,"pct":1.0},"disks":[],"network":[],"newfield":true}"""))
        assertEquals(1.0, api.metrics(target(), "tok").cpu.usagePct, 0.001)
    }

    @Test fun unauthorized_throwsApiException() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        try { runBlocking { api.metrics(target(), "bad") }; fail("expected") }
        catch (e: ApiException) { assertEquals(401, e.code) }
    }

    @Test fun health_trueOn200() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(api.health(target(), "tok"))
    }

    @Test fun history_parsesPoints() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"metric":"cpu","interval_s":2,"points":[{"t":1,"v":9.0}]}"""))
        assertEquals(9.0, api.history(target(), "tok", "cpu", 10).points[0].v, 0.001)
    }
}
