package com.example.servex.data

import com.example.servex.domain.model.Server
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCodecTest {
    @Test fun roundTrip() {
        val list = listOf(Server("1", "web", "http://10.0.0.5:8765", null, 5, true))
        assertEquals(list, ServerStore.decode(ServerStore.encode(list)))
    }

    @Test fun emptyIsEmptyList() {
        assertEquals(emptyList<Server>(), ServerStore.decode(null))
        assertEquals(emptyList<Server>(), ServerStore.decode(""))
    }
}
