package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A row a Shared group holds keeps what the budget link set through
 * [TransactionRepository.updateTransaction], the path every edit, rule,
 * re-parse and bulk update takes.
 */
class TransactionRepositoryHoldTest {
    private val dao = mockk<TransactionDao>(relaxed = true)
    private val goals = mockk<GoalRepository>(relaxed = true)
    private var hold: TransactionHold? = null
    private val repo = TransactionRepository(dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), goals) { hold }
    private val written = slot<TransactionEntity>()

    private val stored = TransactionEntity(
        id = 9, amount = BigDecimal("1000"), merchantName = "Shop", category = "Food",
        transactionType = TransactionType.EXPENSE, dateTime = LocalDateTime.of(2026, 11, 10, 12, 0),
        transactionHash = "h9", currency = "INR", lentSharesTotal = BigDecimal("600"),
    )

    @Before
    fun setUp() {
        coEvery { goals.writeAndCap<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        coEvery { dao.getShareAmounts(9) } returns emptyList()
        coEvery { dao.getTransactionById(9) } answers { stored }
        coEvery { dao.updateTransaction(capture(written)) } returns Unit
    }

    @Test
    fun `a linked expense keeps its lent value through an edit with a stale copy`() = runBlocking {
        hold = TransactionHold.SHARED_EXPENSE
        repo.updateTransaction(stored.copy(category = "Groceries", lentSharesTotal = null))
        assertEquals(0, BigDecimal("600").compareTo(written.captured.lentSharesTotal))
        assertEquals("Groceries", written.captured.category)
    }

    @Test
    fun `a linked expense stays an expense in its currency, at least its lent value`() = runBlocking {
        hold = TransactionHold.SHARED_EXPENSE
        repo.updateTransaction(stored.copy(amount = BigDecimal("100"), currency = "USD", transactionType = TransactionType.INCOME))
        assertEquals(TransactionType.EXPENSE, written.captured.transactionType)
        assertEquals("INR", written.captured.currency)
        assertEquals(0, BigDecimal("1000").compareTo(written.captured.amount))
    }

    @Test
    fun `a Settlement payment stays uncounted`() = runBlocking {
        hold = TransactionHold.SHARED_SETTLEMENT
        repo.updateTransaction(stored.copy(lentSharesTotal = null, excludedFromAnalytics = false))
        assertTrue(written.captured.excludedFromAnalytics)
        assertNull(written.captured.lentSharesTotal)
    }

    @Test
    fun `an entry keeps the amount, currency and date the Shared expense gave it`() = runBlocking {
        hold = TransactionHold.SHARED_ENTRY
        repo.updateTransaction(stored.copy(amount = BigDecimal("5"), dateTime = LocalDateTime.of(2026, 1, 1, 0, 0), category = "Travel"))
        assertEquals(0, BigDecimal("1000").compareTo(written.captured.amount))
        assertEquals(stored.dateTime, written.captured.dateTime)
        assertEquals("Travel", written.captured.category)
    }

    @Test
    fun `an ordinary row still takes its total from its split shares`() = runBlocking {
        hold = null
        repo.updateTransaction(stored.copy(category = "Travel"))
        assertNull(written.captured.lentSharesTotal)
    }
}
