package com.pennywiseai.tracker.data.sharedexpenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedServerAddressTest {

    @Test
    fun `https addresses are accepted and tidied`() {
        assertEquals("https://shared.example.dev", SharedServerAddress.normalize("https://shared.example.dev"))
        assertEquals("https://shared.example.dev", SharedServerAddress.normalize("  https://shared.example.dev/  "))
        assertEquals("https://shared.example.dev", SharedServerAddress.normalize("HTTPS://Shared.Example.dev"))
        assertEquals("https://shared.example.dev:8443/api", SharedServerAddress.normalize("https://shared.example.dev:8443/api/"))
    }

    @Test
    fun `plain http only for localhost and 127_0_0_1`() {
        assertEquals("http://localhost:8790", SharedServerAddress.normalize("http://localhost:8790"))
        assertEquals("http://127.0.0.1:8790", SharedServerAddress.normalize("http://127.0.0.1:8790/"))
        assertEquals("http://localhost", SharedServerAddress.normalize("http://LOCALHOST"))
        assertNull(SharedServerAddress.normalize("http://10.0.2.2:8790"))
        assertNull(SharedServerAddress.normalize("http://shared.example.dev"))
        assertNull(SharedServerAddress.normalize("http://localhost.example.dev"))
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "", "   ", "shared.example.dev", "ftp://shared.example.dev", "https://", "https:///path",
            "https://user:pw@shared.example.dev", "https://shared.example.dev/?x=1", "https://shared.example.dev/#top",
            "https://shared example.dev", "pennywise://shared/join",
        ).forEach { assertNull(it, SharedServerAddress.normalize(it)) }
    }

    @Test
    fun `invite links give the server and token`() {
        val token = "A".repeat(20) + "b_-" + "9".repeat(20)
        assertEquals(
            SharedInviteLink("https://shared.example.dev", token),
            SharedInviteLink.parse("https://shared.example.dev/join/$token"),
        )
        assertEquals(
            SharedInviteLink("http://localhost:8790", token),
            SharedInviteLink.parse("pennywise://shared/join?server=http%3A%2F%2Flocalhost%3A8790&token=$token"),
        )
        assertNull(SharedInviteLink.parse("https://shared.example.dev/join/short"))
        assertNull(SharedInviteLink.parse("http://shared.example.dev/join/$token"))
        assertNull(SharedInviteLink.parse("pennywise://add?amount=1"))
        assertNull(SharedInviteLink.parse("not a link"))
    }
}
