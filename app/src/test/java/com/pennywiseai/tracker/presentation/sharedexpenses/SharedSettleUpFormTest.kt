package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.Debt
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Settle-up defaults and checks. Generic names only. */
class SharedSettleUpFormTest {

    private val me = "m-me"
    private val a = "m-a"
    private val b = "m-b"
    private val members = listOf(
        EditorMember(me, "Me", isMe = true),
        EditorMember(a, "Member A", isMe = false),
        EditorMember(b, "Member B", isMe = false),
    )
    private val today = LocalDate.of(2026, 11, 12)
    private val ids = members.map { it.id }.toSet()

    private fun debt(from: String, to: String, amount: String, currency: String = "INR") = Debt(from, to, BigDecimal(amount), currency)
    private fun debts(vararg d: Debt) = d.groupBy { it.currency }

    private fun defaults(debts: Map<String, List<Debt>>, from: String? = null, to: String? = null, amount: String? = null, currency: String? = null) =
        settleUpDefaults(me, members, debts, "INR", today, from, to, amount, currency)

    @Test fun `the key's pair and amount win`() {
        val f = defaults(debts(debt(me, a, "100")), from = b, to = a, amount = "250.50", currency = "USD")
        assertEquals(b, f.fromId)
        assertEquals(a, f.toId)
        assertEquals("250.50", f.amount)
        assertEquals("USD", f.currency)
        assertTrue(f.amountEdited)
        assertEquals(today, f.date)
    }

    @Test fun `a key pair without an amount takes the debt between them`() {
        val f = defaults(debts(debt(b, a, "75.00")), from = b, to = a)
        assertEquals("75", f.amount)
        assertFalse(f.amountEdited)
    }

    @Test fun `only a receiver from the key picks who owes them most as the payer`() {
        // A payment I received, opened from a bank transaction.
        val f = defaults(debts(debt(a, me, "50"), debt(b, me, "200")), to = me, amount = "1000", currency = "INR")
        assertEquals(b, f.fromId)
        assertEquals(me, f.toId)
        // Nobody owes me: still someone else, never me paying myself.
        val none = defaults(emptyMap(), to = me)
        assertEquals(me, none.toId)
        assertTrue(none.fromId != null && none.fromId != me)
    }

    @Test fun `only a payer from the key picks who they owe most as the receiver`() {
        val f = defaults(debts(debt(me, a, "50"), debt(me, b, "200")), from = me, amount = "1000", currency = "INR")
        assertEquals(me, f.fromId)
        assertEquals(b, f.toId)
    }

    @Test fun `without a key I pay the member I owe most`() {
        val f = defaults(debts(debt(me, a, "100"), debt(me, b, "300"), debt(a, me, "999")))
        assertEquals(me, f.fromId)
        assertEquals(b, f.toId)
        assertEquals("300", f.amount)
    }

    @Test fun `the group's currency comes before a larger debt in another`() {
        val f = defaults(debts(debt(me, a, "100"), debt(me, b, "5000", "USD")))
        assertEquals(a, f.toId)
        assertEquals("INR", f.currency)
    }

    @Test fun `owing nobody, the member who owes me most pays me`() {
        val f = defaults(debts(debt(a, me, "40"), debt(b, me, "60")))
        assertEquals(b, f.fromId)
        assertEquals(me, f.toId)
        assertEquals("60", f.amount)
    }

    @Test fun `with no debts I pay the first other member, amount blank`() {
        val f = defaults(emptyMap())
        assertEquals(me, f.fromId)
        assertEquals(a, f.toId)
        assertEquals("", f.amount)
        assertEquals("INR", f.currency)
    }

    @Test fun `debts in another currency use that currency`() {
        val f = defaults(debts(debt(me, a, "12.50", "USD")))
        assertEquals("USD", f.currency)
        assertEquals("12.5", f.amount)
    }

    @Test fun `changing the pair refills the amount unless it was typed`() {
        val d = debts(debt(me, a, "100"), debt(me, b, "300"))
        val f = defaults(d)
        assertEquals("100", f.copy(toId = a).refill(d).amount)
        assertEquals("", f.copy(toId = me, fromId = a).refill(d).amount)
        assertEquals("42", f.copy(amount = "42", amountEdited = true, toId = a).refill(d).amount)
    }

    @Test fun `checking a settlement`() {
        val ok = SettleForm(fromId = me, toId = a, amount = "100,5", currency = "INR", date = today)
        val (draft, error) = checkSettlement(ok, ids)
        assertNull(error)
        assertNotNull(draft)
        assertEquals(0, BigDecimal("100.5").compareTo(draft!!.amount))

        assertEquals(SharedExpenseError.SETTLEMENT_SAME_MEMBER, checkSettlement(ok.copy(toId = me), ids).second)
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, checkSettlement(ok.copy(amount = ""), ids).second)
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, checkSettlement(ok.copy(amount = "0"), ids).second)
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, checkSettlement(ok.copy(toId = null), ids).second)
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, checkSettlement(ok.copy(toId = "m-stranger"), ids).second)
    }

    @Test fun `UPI is offered only when I pay someone in INR`() {
        val f = SettleForm(fromId = me, toId = a, currency = "INR")
        assertTrue(canPayWithUpi(f, me))
        assertFalse(canPayWithUpi(f.copy(currency = "USD"), me))
        assertFalse(canPayWithUpi(f.copy(fromId = a, toId = me), me))
        assertFalse(canPayWithUpi(f, null))
    }
}
