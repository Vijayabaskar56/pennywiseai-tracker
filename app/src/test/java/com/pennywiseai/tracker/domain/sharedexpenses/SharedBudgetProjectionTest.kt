package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.assertAmount
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.d
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.expense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.payers
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.shares
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedBudgetProjectionTest {
    private fun assertProjection(paid: String, share: String, linked: Boolean, lent: String?, entry: String?) {
        val result = SharedBudgetProjection.project(d(paid), d(share), linked)
        assertAmount(lent, result.lentSharesTotal)
        assertAmount(entry, result.entryAmount)
    }

    @Test
    fun `I paid more than my share`() {
        assertProjection(paid = "1000.00", share = "250.00", linked = true, lent = "750.00", entry = null)
        assertProjection(paid = "1000.00", share = "250.00", linked = false, lent = null, entry = "250.00")
    }

    @Test
    fun `I paid less than my share`() {
        assertProjection(paid = "100.00", share = "250.00", linked = true, lent = "0", entry = "150.00")
        assertProjection(paid = "100.00", share = "250.00", linked = false, lent = null, entry = "250.00")
    }

    @Test
    fun `I paid exactly my share`() {
        assertProjection(paid = "250.00", share = "250.00", linked = true, lent = "0", entry = null)
        assertProjection(paid = "250.00", share = "250.00", linked = false, lent = null, entry = "250.00")
    }

    @Test
    fun `I paid nothing`() {
        assertProjection(paid = "0", share = "250.00", linked = true, lent = "0", entry = "250.00")
        assertProjection(paid = "0", share = "250.00", linked = false, lent = null, entry = "250.00")
    }

    @Test
    fun `I have no share`() {
        assertProjection(paid = "1000.00", share = "0", linked = true, lent = "1000.00", entry = null)
        assertProjection(paid = "1000.00", share = "0", linked = false, lent = null, entry = null)
    }

    @Test
    fun `zero lent keeps the amounts' scale`() {
        assertEquals(d("0.00"), SharedBudgetProjection.project(d("100.00"), d("250.00"), linked = true).lentSharesTotal)
    }

    @Test
    fun `projection for a member of an expense`() {
        val e = expense(payers("m1" to "600.00", "m2" to "300.00"), shares("m1" to "300.00", "m2" to "300.00", "m3" to "300.00"))
        assertAmount("600.00", SharedBudgetProjection.paidBy(e, "m1"))
        assertAmount("0", SharedBudgetProjection.paidBy(e, "m3"))
        assertAmount("300.00", SharedBudgetProjection.shareOf(e, "m3"))
        assertAmount("0", SharedBudgetProjection.shareOf(e, "m9"))

        assertEquals(BudgetProjection(d("300.00"), null), SharedBudgetProjection.project(e, "m1", linked = true))
        assertEquals(BudgetProjection(d("0.00"), null), SharedBudgetProjection.project(e, "m2", linked = true))
        assertEquals(BudgetProjection(null, d("300.00")), SharedBudgetProjection.project(e, "m3", linked = false))
        assertEquals(BudgetProjection(null, null), SharedBudgetProjection.project(e, "m9", linked = false))
    }

    @Test
    fun `a deleted expense projects nothing`() {
        val e = expense(payers("m1" to "100.00"), shares("m1" to "50.00", "m2" to "50.00"), deletedAt = 1L)
        assertEquals(BudgetProjection(null, null), SharedBudgetProjection.project(e, "m1", linked = true))
        assertEquals(BudgetProjection(null, null), SharedBudgetProjection.project(e, "m2", linked = false))
    }
}
