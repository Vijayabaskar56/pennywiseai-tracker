package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseTemplate
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The expense form's state and live preview. Generic names only. */
class SharedExpenseEditorFormTest {

    private val me = "m-c"
    private val a = "m-a"
    private val b = "m-b"
    private val members = listOf(
        EditorMember(me, "Me", isMe = true),
        EditorMember(a, "Member A", isMe = false),
        EditorMember(b, "Member B", isMe = false),
    )
    private val group = SharedGroup(id = "g", name = "Flat", defaultCurrency = "INR", createdAt = 0, updatedAt = 0, rev = 1, myMemberId = me)
    private val today = LocalDate.of(2026, 11, 12)

    private fun d(v: String) = BigDecimal(v)
    private fun assertAmount(expected: String, actual: BigDecimal?) =
        assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual))

    private fun form(amount: String = "1000", method: SplitMethod = SplitMethod.EQUAL) =
        newExpenseForm(group, members, today).copy(description = "Groceries", amount = amount, method = method)

    // ── Typing numbers ──

    @Test fun `number fields take a point or a comma, within the decimals allowed`() {
        assertTrue(acceptNumberInput("", 2))
        assertTrue(acceptNumberInput("12.5", 2))
        assertTrue(acceptNumberInput("12,50", 2))
        assertTrue(acceptNumberInput("12,", 2))
        assertFalse(acceptNumberInput("12.555", 2))
        assertFalse(acceptNumberInput("1.2.3", 2))
        assertFalse(acceptNumberInput("1,2.3", 2))
        assertFalse(acceptNumberInput("-5", 2))
        assertFalse(acceptNumberInput("1 000", 2))
        assertFalse(acceptNumberInput("5.", 0)) // no decimals in JPY
        assertTrue(acceptNumberInput("500", 0))
    }

    @Test fun `typed numbers read the same in comma locales`() {
        assertAmount("12.5", parseNumberInput("12,5"))
        assertAmount("12.5", parseNumberInput("12.5"))
        assertAmount("12", parseNumberInput("12,"))
        assertAmount("0.5", parseNumberInput(",5"))
        assertNull(parseNumberInput(""))
        assertNull(parseNumberInput("."))
        assertEquals("33.5", canonicalNumber("33,5"))
        assertEquals("50", canonicalNumber("50."))
    }

    @Test fun `stored amounts show without trailing zeros`() {
        assertEquals("1240", amountFieldText(d("1240.00")))
        assertEquals("12.5", amountFieldText(d("12.50")))
        assertEquals("1000", amountFieldText(d("1E+3")))
    }

    // ── Defaults ──

    @Test fun `a new expense is paid by me and shared equally by everyone`() {
        val f = newExpenseForm(group, members, today)
        assertEquals(me, f.payerId)
        assertEquals(PaidBy.ONE, f.paidBy)
        assertEquals(setOf(me, a, b), f.equalIds)
        assertEquals(mapOf(me to 1, a to 1, b to 1), f.shareCounts)
        assertEquals("INR", f.currency)
        assertEquals(today, f.date)
        assertNull(f.repeat)
    }

    @Test fun `members offered are active ones plus removed ones the row names, me first`() {
        val seats = listOf(
            SharedMember("m-z", "g", "Zed", joined = true, rev = 1),
            SharedMember(me, "g", "Me", joined = true, rev = 1),
            SharedMember("m-old", "g", "Old", joined = true, removedAt = 5, rev = 1),
            SharedMember("m-gone", "g", "Gone", joined = true, removedAt = 5, rev = 1),
        )
        val offered = editorMembers(seats, me, named = setOf("m-old"))
        assertEquals(listOf(me, "m-z", "m-old"), offered.map { it.id })
        assertTrue(offered.last().removed)
    }

    @Test fun `a transaction fills description, amount, currency, date and category`() {
        val f = newExpenseForm(group, members, today).fromTransaction("Shop", d("1240.50"), "usd", LocalDate.of(2026, 11, 1), "Groceries")
        assertEquals("Shop", f.description)
        assertEquals("1240.5", f.amount)
        assertEquals("USD", f.currency)
        assertEquals(LocalDate.of(2026, 11, 1), f.date)
        assertEquals("Groceries", f.category)
        // An unknown currency keeps the group's.
        assertEquals("INR", newExpenseForm(group, members, today).fromTransaction("Shop", d("1"), "XYZW", today, null).currency)
    }

    // ── Split preview per method ──

    @Test fun `equal split shows each member's share, leftover to the lowest ID`() {
        val p = previewExpense(form(), members)
        assertAmount("333.34", p.shares[a])
        assertAmount("333.33", p.shares[b])
        assertAmount("333.33", p.shares[me])
        assertNull(p.error)
        assertNotNull(p.draft)
        assertNull(p.sharesLeft)
    }

    @Test fun `equal split leaves out unticked members`() {
        val p = previewExpense(form().copy(equalIds = setOf(a, me)), members)
        assertEquals(setOf(a, me), p.shares.keys)
        assertAmount("500", p.shares[a])
    }

    @Test fun `exact split shows what is left to assign, then too much`() {
        val f = form(method = SplitMethod.EXACT).copy(exactAmounts = mapOf(a to "500", b to "300"))
        val p = previewExpense(f, members)
        assertAmount("200", p.sharesLeft)
        assertEquals(SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT, p.error)
        assertTrue(p.shares.isEmpty())
        assertNull(p.draft)

        val over = previewExpense(f.copy(exactAmounts = mapOf(a to "500", b to "300", me to "300")), members)
        assertAmount("-100", over.sharesLeft)

        val done = previewExpense(f.copy(exactAmounts = mapOf(a to "500", b to "300", me to "200,00")), members)
        assertAmount("0", done.sharesLeft)
        assertNull(done.error)
        assertAmount("200", done.shares[me])
    }

    @Test fun `exact split treats zero and blank as no share`() {
        val p = previewExpense(form(method = SplitMethod.EXACT).copy(exactAmounts = mapOf(a to "1000", b to "0", me to "")), members)
        assertEquals(setOf(a), p.shares.keys)
        assertNull(p.error)
    }

    @Test fun `percent split shows the percent left and each share once it is 100`() {
        val f = form(method = SplitMethod.PERCENT).copy(percents = mapOf(a to "50", b to "30"))
        val p = previewExpense(f, members)
        assertAmount("20", p.sharesLeft)
        assertEquals(SharedExpenseError.PERCENT_NOT_100, p.error)

        val done = previewExpense(f.copy(percents = mapOf(a to "50", b to "30", me to "20")), members)
        assertAmount("0", done.sharesLeft)
        assertAmount("500", done.shares[a])
        assertAmount("300", done.shares[b])
        assertAmount("200", done.shares[me])
        assertEquals("50", done.draft!!.shares.first { it.memberId == a }.weight)
    }

    @Test fun `percent typed with a comma is sent as a point`() {
        val f = form(method = SplitMethod.PERCENT).copy(percents = mapOf(a to "33,5", b to "66,5"))
        val p = previewExpense(f, members)
        assertNull(p.error)
        assertEquals("33.5", p.draft!!.shares.first { it.memberId == a }.weight)
    }

    @Test fun `shares split weighs by whole counts and leaves out zero`() {
        val f = form(method = SplitMethod.SHARES).copy(shareCounts = mapOf(a to 2, b to 1, me to 1))
        val p = previewExpense(f, members)
        assertAmount("500", p.shares[a])
        assertAmount("250", p.shares[b])
        assertAmount("250", p.shares[me])
        assertEquals("2", p.draft!!.shares.first { it.memberId == a }.weight)

        val noB = previewExpense(f.copy(shareCounts = mapOf(a to 1, b to 0, me to 1)), members)
        assertEquals(setOf(a, me), noB.shares.keys)
    }

    @Test fun `nobody taking part asks who shares`() {
        assertEquals(SharedExpenseError.NO_SHARES, previewExpense(form().copy(equalIds = emptySet()), members).error)
    }

    // ── Paid by ──

    @Test fun `several payers show what is left to assign`() {
        val f = form().copy(paidBy = PaidBy.SEVERAL, payerAmounts = mapOf(me to "600", a to "300"))
        val p = previewExpense(f, members)
        assertAmount("100", p.payersLeft)
        assertEquals(SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT, p.error)

        val done = previewExpense(f.copy(payerAmounts = mapOf(me to "600", a to "400")), members)
        assertAmount("0", done.payersLeft)
        assertNull(done.error)
        assertEquals(listOf(a, me), done.draft!!.payers.map { it.memberId })
    }

    @Test fun `one payer pays the whole amount and shows nothing left`() {
        val p = previewExpense(form(), members)
        assertNull(p.payersLeft)
        assertEquals(listOf(PayerPart(me, d("1000"))), p.draft!!.payers)
    }

    // ── Validation and inline errors ──

    @Test fun `problems come in the order the form reads`() {
        assertEquals(SharedExpenseError.DESCRIPTION_BLANK, previewExpense(form().copy(description = " "), members).error)
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, previewExpense(form(amount = ""), members).error)
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, previewExpense(form(amount = "10.5").copy(currency = "JPY"), members).error)
    }

    @Test fun `an inline problem shows only once its section is touched`() {
        val f = form().copy(description = "")
        val p = previewExpense(f, members)
        assertNull(p.errorAt(FormField.DESCRIPTION, f.touched))
        assertEquals(SharedExpenseError.DESCRIPTION_BLANK, p.errorAt(FormField.DESCRIPTION, f.touch(FormField.DESCRIPTION).touched))
        assertNull(p.errorAt(FormField.AMOUNT, setOf(FormField.AMOUNT)))
    }

    @Test fun `a member not offered is left out of the split`() {
        val p = previewExpense(form().copy(equalIds = setOf(a, "m-stranger")), members)
        assertEquals(setOf(a), p.shares.keys)
    }

    // ── Loading a stored row ──

    @Test fun `editing an expense loads its payers, method and weights`() {
        val expense = SharedExpense(
            id = "e", groupId = "g", description = "Rent", amount = d("1000.00"), currency = "INR", date = today,
            category = "Rent", notes = "n", splitMethod = SplitMethod.PERCENT,
            payers = listOf(PayerPart(me, d("600.00")), PayerPart(a, d("400.00"))),
            shares = listOf(SharePart(a, d("500.00"), "50"), SharePart(me, d("500.00"), "50")),
            createdBy = me, updatedBy = me, createdAt = 0, updatedAt = 0, version = 1, rev = 1,
        )
        val f = expenseForm(expense, members)
        assertEquals("1000", f.amount)
        assertEquals(PaidBy.SEVERAL, f.paidBy)
        assertEquals(mapOf(me to "600", a to "400"), f.payerAmounts)
        assertEquals(mapOf(a to "50", me to "50"), f.percents)
        // The other methods start from their defaults.
        assertEquals(setOf(me, a, b), f.equalIds)
        val p = previewExpense(f, members)
        assertNull(p.error)
        assertEquals(expense.shares.map { it.memberId }.toSet(), p.shares.keys)
    }

    @Test fun `editing a recurring expense uses its next date and interval`() {
        val template = ExpenseTemplate(
            description = "Rent", amount = d("900"), currency = "INR", splitMethod = SplitMethod.SHARES,
            payers = listOf(PayerPart(me, d("900"))), shares = listOf(SharePart(a, d("600"), "2"), SharePart(me, d("300"), "1")),
        )
        val next = LocalDate.of(2026, 12, 1)
        val f = recurringForm(template, RecurringInterval.MONTHLY, next, members)
        assertEquals(next, f.date)
        assertEquals(RecurringInterval.MONTHLY, f.repeat)
        assertEquals(mapOf(a to 2, me to 1), f.shareCounts)
        assertEquals(me, f.payerId)
        assertNull(previewExpense(f, members).error)
    }
}
