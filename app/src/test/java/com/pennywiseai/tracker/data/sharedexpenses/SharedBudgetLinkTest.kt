package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.repository.TransactionHold
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.expense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.payers
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.settlement
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.shares
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * The budget link (docs/shared-expenses.md, "Link to your budget"). Me is
 * member "a" of group "g1"; "b" and "c" are the others.
 */
class SharedBudgetLinkTest {
    private val store = FakeSharedLinkStore()
    private val ledger = FakeBudgetLedger()
    private val link = SharedBudgetLink(store, ledger) { 1_000L }

    private fun d(v: String) = BigDecimal(v)

    private fun add(e: SharedExpense) = e.also { store.expenses[it.id] = it }

    private suspend fun entry(e: SharedExpense): TransactionEntity? = ledger.liveByHash(SharedBudgetLink.entryHash(e.id))

    private fun assertAmount(expected: String?, actual: BigDecimal?) {
        if (expected == null) assertNull(actual) else assertEquals(0, d(expected).compareTo(actual!!))
    }

    @Before
    fun setUp() {
        store.memberIds["g1"] = "a"
        store.groupNames["g1"] = "Flat"
    }

    // ── The P/S table ──

    private data class Row(val paid: List<Pair<String, String>>, val owed: List<Pair<String, String>>, val lent: String?, val entry: String?)

    private fun checkRow(row: Row, linked: Boolean): Unit = runBlocking {
        val e = add(expense(payers(*row.paid.toTypedArray()), shares(*row.owed.toTypedArray())))
        val tx = ledger.add(1, e.amount.toPlainString())
        if (linked) {
            assertTrue(link.linkExpense(tx.id, e.id) is SharedResult.Success)
            assertAmount(row.lent, ledger.byId(1)!!.lentSharesTotal)
        } else {
            link.reconcile()
            assertNull(ledger.byId(1)!!.lentSharesTotal)
        }
        val made = entry(e)
        assertAmount(row.entry, made?.amount)
        made?.let {
            assertEquals("INR", it.currency)
            assertEquals(TransactionType.EXPENSE, it.transactionType)
            assertEquals(e.date, it.dateTime.toLocalDate())
            assertNull(it.smsBody)
            assertNull(it.accountNumber)
        }
    }

    @Test
    fun `I paid all of it, linked - the bank payment counts only my share`() =
        checkRow(Row(listOf("a" to "1000"), listOf("a" to "400", "b" to "600"), lent = "600", entry = null), linked = true)

    @Test
    fun `I paid all of it, unlinked - the entry is my share`() =
        checkRow(Row(listOf("a" to "1000"), listOf("a" to "400", "b" to "600"), lent = null, entry = "400"), linked = false)

    @Test
    fun `someone else paid - the entry is my share`() =
        checkRow(Row(listOf("b" to "1000"), listOf("a" to "400", "b" to "600"), lent = null, entry = "400"), linked = false)

    @Test
    fun `I paid less than my share, linked - nothing lent and the rest is an entry`() =
        checkRow(Row(listOf("a" to "300", "b" to "700"), listOf("a" to "500", "b" to "500"), lent = "0", entry = "200"), linked = true)

    @Test
    fun `I paid less than my share, unlinked - the entry is all my share`() =
        checkRow(Row(listOf("a" to "300", "b" to "700"), listOf("a" to "500", "b" to "500"), lent = null, entry = "500"), linked = false)

    @Test
    fun `I paid exactly my share, linked - nothing lent and no entry`() =
        checkRow(Row(listOf("a" to "500", "b" to "500"), listOf("a" to "500", "b" to "500"), lent = "0", entry = null), linked = true)

    @Test
    fun `I paid for others only, linked - all of it is lent and no entry`() =
        checkRow(Row(listOf("a" to "900"), listOf("b" to "450", "c" to "450"), lent = "900", entry = null), linked = true)

    @Test
    fun `I have no share and did not pay - no entry`() =
        checkRow(Row(listOf("b" to "900"), listOf("b" to "450", "c" to "450"), lent = null, entry = null), linked = false)

    // ── Running again, edits, deletes ──

    @Test
    fun `a second reconcile changes nothing`() = runBlocking {
        val mine = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        add(expense(payers("b" to "300"), shares("a" to "150", "b" to "150")))
        ledger.add(1, "1000")
        link.linkExpense(1, mine.id)
        link.reconcile()
        val ledgerWrites = ledger.writes
        val storeWrites = store.writes
        val before = ledger.rows.toMap()
        link.reconcile()
        link.reconcile()
        assertEquals(ledgerWrites, ledger.writes)
        assertEquals(storeWrites, store.writes)
        assertEquals(before, ledger.rows.toMap())
    }

    @Test
    fun `an edit that changes my share updates the linked value and the entry`() = runBlocking {
        val e = add(expense(payers("a" to "300", "b" to "700"), shares("a" to "500", "b" to "500")))
        ledger.add(1, "300")
        link.linkExpense(1, e.id)
        assertAmount("200", entry(e)?.amount)

        add(e.copy(payers = payers("a" to "900", "b" to "100"), shares = shares("a" to "200", "b" to "800"), version = 2))
        link.reconcile()
        assertAmount("700", ledger.byId(1)!!.lentSharesTotal)
        assertNull(entry(e)) // my payment now covers my share

        add(e.copy(payers = payers("a" to "100", "b" to "900"), shares = shares("a" to "600", "b" to "400"), version = 3))
        link.reconcile()
        assertAmount("0", ledger.byId(1)!!.lentSharesTotal)
        assertAmount("500", entry(e)?.amount)
    }

    @Test
    fun `an entry the user deleted stays deleted through edits`() = runBlocking {
        val e = add(expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")))
        link.reconcile()
        val made = entry(e)!!
        ledger.softDelete(made.id)

        add(e.copy(shares = shares("a" to "500", "b" to "500"), version = 2))
        link.reconcile()
        link.reconcile()
        assertNull(entry(e))
        assertEquals(1, ledger.rows.size)
    }

    @Test
    fun `a deleted linked transaction drops the link and the entry becomes my share`() = runBlocking {
        val e = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "1000")
        link.linkExpense(1, e.id)
        assertNull(entry(e))

        ledger.softDelete(1)
        link.reconcile()
        assertTrue(store.links().isEmpty())
        assertAmount("400", entry(e)?.amount)
    }

    @Test
    fun `a deleted expense removes its entry and lent value, and restoring it brings both back`() = runBlocking {
        val e = add(expense(payers("a" to "300", "b" to "700"), shares("a" to "500", "b" to "500")))
        ledger.add(1, "300")
        link.linkExpense(1, e.id)
        assertAmount("0", ledger.byId(1)!!.lentSharesTotal)
        assertAmount("200", entry(e)?.amount)

        add(e.copy(deletedAt = 5L))
        link.reconcile()
        assertNull(ledger.byId(1)!!.lentSharesTotal) // counts in full again
        assertNull(entry(e))
        assertTrue(store.projectionRows.isEmpty())
        assertEquals(1, store.links().size)

        add(e.copy(deletedAt = null))
        link.reconcile()
        assertAmount("0", ledger.byId(1)!!.lentSharesTotal)
        assertAmount("200", entry(e)?.amount)
    }

    @Test
    fun `the entry follows the expense but keeps a category the user changed`() = runBlocking {
        val e = add(expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")).copy(category = "Food"))
        link.reconcile()
        val made = entry(e)!!
        assertEquals("Food", made.category)
        assertEquals("Expense", made.merchantName)
        ledger.update(made.copy(category = "Groceries"))

        add(e.copy(description = "Dinner", shares = shares("a" to "450", "b" to "550"), version = 2))
        link.reconcile()
        val after = entry(e)!!
        assertEquals("Groceries", after.category)
        assertEquals("Dinner", after.merchantName)
        assertAmount("450", after.amount)

        add(e.copy(description = "Dinner", category = "Travel", shares = shares("a" to "450", "b" to "550"), version = 3))
        link.reconcile()
        assertEquals("Travel", entry(e)!!.category) // the expense's own category changed
    }

    @Test
    fun `a group gone from this phone keeps its entries and linked values`() = runBlocking {
        val mine = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        val theirs = add(expense(payers("b" to "300"), shares("a" to "150", "b" to "150")))
        ledger.add(1, "1000")
        link.linkExpense(1, mine.id)
        link.reconcile()

        store.memberIds.clear()
        store.expenses.clear()
        val before = ledger.rows.toMap()
        link.reconcile()
        assertEquals(before, ledger.rows.toMap())
        assertAmount("600", ledger.byId(1)!!.lentSharesTotal)
        assertAmount("150", entry(theirs)?.amount)
    }

    @Test
    fun `a re-parse that gives the payment a new id keeps the link`() = runBlocking {
        val e = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "1000", hash = "sms-x")
        link.linkExpense(1, e.id)
        ledger.rows.remove(1L)
        ledger.add(7, "1000", hash = "sms-x")

        link.reconcile()
        assertEquals(7L, store.links().single().transactionId)
        assertAmount("600", ledger.byId(7)!!.lentSharesTotal)
        assertNull(entry(e))
    }

    @Test
    fun `entries in two currencies stay apart`() = runBlocking {
        val inr = add(expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")))
        val usd = add(expense(payers("b" to "30"), shares("a" to "10", "b" to "20"), currency = "USD"))
        link.reconcile()
        assertEquals("INR", entry(inr)!!.currency)
        assertAmount("400", entry(inr)!!.amount)
        assertEquals("USD", entry(usd)!!.currency)
        assertAmount("10", entry(usd)!!.amount)
    }

    @Test
    fun `a payment in another currency is refused`() = runBlocking {
        val e = add(expense(payers("a" to "30"), shares("a" to "10", "b" to "20"), currency = "USD"))
        ledger.add(1, "30", currency = "INR")
        val r = link.linkExpense(1, e.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.CURRENCY_MISMATCH), (r as SharedResult.Failure).error)
        assertTrue(store.links().isEmpty())
        assertNull(ledger.byId(1)!!.lentSharesTotal)
    }

    // ── Linking rules ──

    @Test
    fun `a Split bill cannot be linked`() = runBlocking {
        val e = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "1000", lent = "300")
        ledger.splitShares += 1L
        val r = link.linkExpense(1, e.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.SPLIT_OR_LOAN), (r as SharedResult.Failure).error)
        assertAmount("300", ledger.byId(1)!!.lentSharesTotal)
    }

    @Test
    fun `only an expense I paid on can take my payment`() = runBlocking {
        val e = add(expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "1000")
        val r = link.linkExpense(1, e.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.NOT_A_PAYER), (r as SharedResult.Failure).error)
    }

    @Test
    fun `an entry the app made cannot be linked, and holds report each tie`() = runBlocking {
        val e = add(expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")))
        val mine = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        link.reconcile()
        val made = entry(e)!!
        val r = link.linkExpense(made.id, mine.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.APP_ENTRY), (r as SharedResult.Failure).error)

        ledger.add(1, "1000")
        link.linkExpense(1, mine.id)
        assertEquals(TransactionHold.SHARED_ENTRY, link.holdOf(made))
        assertEquals(TransactionHold.SHARED_EXPENSE, link.holdOf(ledger.byId(1)!!))
        assertNull(link.holdOf(ledger.add(2, "50")))
        val tie = link.tieOf(ledger.byId(1)!!) as SharedTransactionTie.Expense
        assertEquals("Flat", tie.groupName)
        assertAmount("400", tie.myShare)
    }

    @Test
    fun `unlinking an expense gives the payment back in full and the entry returns`() = runBlocking {
        val e = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "1000")
        link.linkExpense(1, e.id)
        link.unlink(1)
        assertNull(ledger.byId(1)!!.lentSharesTotal)
        assertAmount("400", entry(e)?.amount)
    }

    // ── Settlements ──

    @Test
    fun `a payment linked to a Settlement is not counted, and unlinking restores it exactly`() = runBlocking {
        val s = settlement("a", "b", "600").also { store.settlements[it.id] = it }
        val original = ledger.add(1, "600")
        assertTrue(link.linkSettlement(1, s.id) is SharedResult.Success)
        assertTrue(ledger.byId(1)!!.excludedFromAnalytics)
        assertEquals(TransactionHold.SHARED_SETTLEMENT, link.holdOf(ledger.byId(1)!!))

        link.unlink(1)
        assertEquals(original, ledger.byId(1)!!.copy(updatedAt = original.updatedAt))
        assertTrue(store.links().isEmpty())
    }

    @Test
    fun `a credit already excluded stays excluded after unlinking`() = runBlocking {
        val s = settlement("b", "a", "600").also { store.settlements[it.id] = it }
        val original = ledger.add(1, "600", type = TransactionType.INCOME, excluded = true)
        assertTrue(link.linkSettlement(1, s.id) is SharedResult.Success)
        link.unlink(1)
        assertEquals(original, ledger.byId(1)!!.copy(updatedAt = original.updatedAt))
    }

    @Test
    fun `a Settlement takes my debit when I paid and my credit when I received`() = runBlocking {
        val paid = settlement("a", "b", "600").also { store.settlements[it.id] = it }
        ledger.add(1, "600", type = TransactionType.INCOME)
        val r = link.linkSettlement(1, paid.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.NOT_A_DEBIT), (r as SharedResult.Failure).error)

        val others = settlement("b", "c", "600").also { store.settlements[it.id] = it }
        ledger.add(2, "600")
        val r2 = link.linkSettlement(2, others.id)
        assertEquals(SharedError.LinkRefused(SharedLinkRefusal.NOT_A_PARTY), (r2 as SharedResult.Failure).error)
    }

    @Test
    fun `a deleted Settlement gives its payment back`() = runBlocking {
        val s = settlement("a", "b", "600").also { store.settlements[it.id] = it }
        ledger.add(1, "600")
        link.linkSettlement(1, s.id)
        store.settlements[s.id] = s.copy(deletedAt = 9L)
        link.reconcile()
        assertFalse(ledger.byId(1)!!.excludedFromAnalytics)
        assertTrue(store.links().isEmpty())
    }

    @Test
    fun `candidates put the same amount first and leave out linked and split payments`() = runBlocking {
        val e = add(expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")))
        ledger.add(1, "999")
        ledger.add(2, "1000")
        ledger.add(3, "1000", lent = "100")
        ledger.add(4, "1000")
        link.linkExpense(4, e.id)
        val found = link.candidates("INR", credit = false, amount = d("1000"))
        assertEquals(listOf(2L, 1L), found.map { it.transactionId })
        assertTrue(found.first().exactMatch)
        assertEquals(1L, link.linkable(1)?.linked?.transactionId)
    }
}
