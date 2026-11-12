package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedServerCode
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Shared error becomes one of our strings; the server's own message is never shown. */
class SharedErrorTextTest {

    @Test fun `every server code has its own message except busy and internal`() {
        val byCode = SharedServerCode.entries.associateWith { serverCodeRes(it) }
        assertEquals(byCode[SharedServerCode.BUSY], byCode[SharedServerCode.INTERNAL])
        val others = byCode.filterKeys { it != SharedServerCode.INTERNAL }.values
        assertEquals(others.size, others.toSet().size)
    }

    @Test fun `every invalid reason has its own message`() {
        val ids = SharedExpenseError.entries.map { invalidReasonRes(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `server errors use the code, not the server's message`() {
        val text = sharedErrorText(SharedError.Server(409, SharedServerCode.BALANCE_NOT_ZERO, "raw server text"))
        assertEquals(UiText.Res(R.string.shared_error_balance_not_zero), text)
    }

    @Test fun `network errors read as offline and hide the exception`() {
        val error = SharedError.Network("java.net.UnknownHostException")
        assertEquals(UiText.Res(R.string.shared_error_network), sharedErrorText(error))
        assertTrue(error.isOffline)
        assertFalse(SharedError.NotFound.isOffline)
    }

    @Test fun `other server names only the invite's host`() {
        val text = sharedErrorText(SharedError.OtherServer("https://shared.example.org:8443", "https://other.example.org"))
        assertEquals(UiText.Res(R.string.shared_error_other_server, listOf("shared.example.org:8443")), text)
    }

    @Test fun `invalid input maps through its reason`() {
        assertEquals(
            UiText.Res(R.string.shared_invalid_group_name_blank),
            sharedErrorText(SharedError.Invalid(SharedExpenseError.GROUP_NAME_BLANK)),
        )
    }

    @Test fun `server host drops scheme and path and keeps the address when unreadable`() {
        assertEquals("shared.example.org", serverHost("https://shared.example.org/"))
        assertEquals("localhost:8787", serverHost("http://localhost:8787"))
        assertEquals("not a url", serverHost("not a url"))
    }
}
