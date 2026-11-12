package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.d
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.expense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.payers
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.settlement
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.shares
import com.pennywiseai.tracker.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SharedBalancesTest {
    private fun assertSumsToZero(balances: GroupBalances) {
        balances.forEach { (currency, members) ->
            assertEquals("$currency sums to zero", 0, members.values.fold(BigDecimal.ZERO, BigDecimal::add).signum())
        }
    }

    /** Applies [debts] to [balances] as if each were paid as a Settlement. */
    private fun settle(balances: Map<String, BigDecimal>, debts: List<Debt>): Map<String, BigDecimal> {
        val left = balances.toMutableMap()
        debts.forEach {
            left[it.fromMemberId] = left.getValue(it.fromMemberId) + it.amount
            left[it.toMemberId] = left.getValue(it.toMemberId) - it.amount
        }
        return left
    }

    // Three expenses between four members: m1 +110, m2 +70, m3 -30, m4 -150.
    private val tangle = listOf(
        expense(payers("m1" to "200.00"), shares("m1" to "50.00", "m2" to "50.00", "m3" to "50.00", "m4" to "50.00")),
        expense(payers("m2" to "120.00"), shares("m3" to "60.00", "m4" to "60.00")),
        expense(payers("m3" to "80.00"), shares("m1" to "40.00", "m4" to "40.00")),
    )

    @Test
    fun `balance is paid minus share`() {
        val balances = SharedBalances.balances(
            listOf(expense(payers("m1" to "1000.00"), shares("m1" to "333.34", "m2" to "333.33", "m3" to "333.33"))),
            emptyList(),
        )
        assertEquals(mapOf("INR" to mapOf("m1" to d("666.66"), "m2" to d("-333.33"), "m3" to d("-333.33"))), balances)
        assertSumsToZero(balances)
    }

    @Test
    fun `multiple payers`() {
        val balances = SharedBalances.balances(
            listOf(expense(payers("m1" to "600.00", "m2" to "300.00"), shares("m1" to "300.00", "m2" to "300.00", "m3" to "300.00"))),
            emptyList(),
        )
        assertEquals(mapOf("m1" to d("300.00"), "m2" to d("0.00"), "m3" to d("-300.00")), balances["INR"])
        assertSumsToZero(balances)
    }

    @Test
    fun `settlements move balances towards zero`() {
        val expenses = listOf(expense(payers("m1" to "900.00"), shares("m1" to "300.00", "m2" to "300.00", "m3" to "300.00")))
        val partly = SharedBalances.balances(expenses, listOf(settlement("m2", "m1", "300.00"), settlement("m3", "m1", "100.00")))
        assertEquals(mapOf("m1" to d("200.00"), "m2" to d("0.00"), "m3" to d("-200.00")), partly["INR"])
        assertSumsToZero(partly)
    }

    @Test
    fun `two currencies in one group are kept apart`() {
        val balances = SharedBalances.balances(
            listOf(
                expense(payers("m1" to "1000.00"), shares("m1" to "500.00", "m2" to "500.00")),
                expense(payers("m2" to "30.00"), shares("m1" to "10.00", "m2" to "10.00", "m3" to "10.00"), currency = "USD"),
                expense(payers("m3" to "900"), shares("m1" to "300", "m2" to "300", "m3" to "300"), currency = "JPY"),
            ),
            listOf(settlement("m2", "m1", "100.00"), settlement("m1", "m2", "5.00", currency = "USD")),
        )
        assertEquals(setOf("INR", "JPY", "USD"), balances.keys)
        assertEquals(mapOf("m1" to d("400.00"), "m2" to d("-400.00")), balances["INR"])
        assertEquals(mapOf("m1" to d("-5.00"), "m2" to d("15.00"), "m3" to d("-10.00")), balances["USD"])
        assertEquals(mapOf("m1" to d("-300"), "m2" to d("-300"), "m3" to d("600")), balances["JPY"])
        assertSumsToZero(balances)
        assertEquals(
            mapOf("INR" to d("400.00"), "JPY" to d("-300"), "USD" to d("-5.00")),
            SharedBalances.forMember(balances, "m1"),
        )
        assertEquals(mapOf("JPY" to d("600"), "USD" to d("-10.00")), SharedBalances.forMember(balances, "m3"))
    }

    @Test
    fun `deleted expenses and settlements are ignored`() {
        val balances = SharedBalances.balances(
            listOf(
                expense(payers("m1" to "100.00"), shares("m1" to "50.00", "m2" to "50.00")),
                expense(payers("m2" to "500.00"), shares("m1" to "500.00"), deletedAt = 1L),
                expense(payers("m3" to "10.00"), shares("m3" to "5.00", "m4" to "5.00"), currency = "USD", deletedAt = 1L),
            ),
            listOf(settlement("m2", "m1", "50.00", deletedAt = 2L)),
        )
        assertEquals(mapOf("INR" to mapOf("m1" to d("50.00"), "m2" to d("-50.00"))), balances)
    }

    @Test
    fun `nothing gives no balances`() {
        assertTrue(SharedBalances.balances(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `simplified debts untangle four members`() {
        val balances = SharedBalances.balances(tangle, emptyList())
        val inr = balances.getValue("INR")
        assertEquals(mapOf("m1" to d("110.00"), "m2" to d("70.00"), "m3" to d("-30.00"), "m4" to d("-150.00")), inr)
        assertSumsToZero(balances)

        val debts = SharedBalances.simplifyDebts(inr, "INR")
        assertEquals(
            listOf(
                Debt("m4", "m1", d("110.00"), "INR"),
                Debt("m4", "m2", d("40.00"), "INR"),
                Debt("m3", "m2", d("30.00"), "INR"),
            ),
            debts,
        )
        assertTrue(debts.size <= inr.size - 1)
        assertTrue(settle(inr, debts).values.all { it.signum() == 0 })
    }

    @Test
    fun `simplified debts break ties by member ID`() {
        val balances = mapOf("m4" to d("-50"), "m2" to d("50"), "m3" to d("-50"), "m1" to d("50"))
        assertEquals(
            listOf(Debt("m3", "m1", d("50.00"), "INR"), Debt("m4", "m2", d("50.00"), "INR")),
            SharedBalances.simplifyDebts(balances, "INR"),
        )
        // The same balances in another order give the same list.
        assertEquals(
            SharedBalances.simplifyDebts(balances, "INR"),
            SharedBalances.simplifyDebts(balances.toSortedMap(), "INR"),
        )
    }

    @Test
    fun `simplified debts skip zero balances`() {
        assertTrue(SharedBalances.simplifyDebts(mapOf("m1" to d("0.00"), "m2" to d("0")), "INR").isEmpty())
        assertTrue(SharedBalances.simplifyDebts(emptyMap(), "INR").isEmpty())
        assertEquals(
            listOf(Debt("m2", "m1", d("10.00"), "INR")),
            SharedBalances.simplifyDebts(mapOf("m1" to d("10.00"), "m2" to d("-10.00"), "m3" to d("0.00")), "INR"),
        )
    }

    @Test
    fun `simplified debts per currency`() {
        val expenses = tangle + expense(payers("m1" to "20.00"), shares("m1" to "10.00", "m2" to "10.00"), currency = "USD")
        val balances = SharedBalances.balances(expenses, emptyList())
        val debts = SharedBalances.simplifiedDebts(balances)
        assertEquals(listOf("INR", "USD"), debts.keys.toList())
        assertEquals(3, debts.getValue("INR").size)
        assertEquals(listOf(Debt("m2", "m1", d("10.00"), "USD")), debts["USD"])
        balances.forEach { (currency, members) ->
            assertTrue(settle(members, debts[currency].orEmpty()).values.all { it.signum() == 0 })
        }
        assertEquals(debts, SharedBalances.debts(simplify = true, expenses = expenses, settlements = emptyList()))
    }

    @Test
    fun `settled group has no simplified debts`() {
        val expenses = listOf(expense(payers("m1" to "100.00"), shares("m1" to "50.00", "m2" to "50.00")))
        assertTrue(SharedBalances.debts(true, expenses, listOf(settlement("m2", "m1", "50.00"))).isEmpty())
    }

    // 400 paid by m1 (250) and m2 (150): nets m1 +200, m2 +100, m3 -150, m4 -150.
    private val fourWay = expense(
        payers("m1" to "250.00", "m2" to "150.00"),
        shares("m1" to "50.00", "m2" to "50.00", "m3" to "150.00", "m4" to "150.00"),
    )

    // 90 paid by m3: nets m3 +60, m1 -30, m4 -30.
    private val threeWay = expense(payers("m3" to "90.00"), shares("m1" to "30.00", "m3" to "30.00", "m4" to "30.00"))

    @Test
    fun `pairwise debts match each expense's debtors to its creditors in member-ID order`() {
        assertEquals(
            mapOf(
                "INR" to listOf(
                    Debt("m3", "m1", d("150.00"), "INR"),
                    Debt("m4", "m1", d("50.00"), "INR"),
                    Debt("m4", "m2", d("100.00"), "INR"),
                ),
            ),
            SharedBalances.pairwiseDebts(listOf(fourWay), emptyList()),
        )
    }

    @Test
    fun `pairwise debts net each pair across expenses`() {
        // m3 owes m1 150 from the first, m1 owes m3 30 from the second: m3 owes m1 120.
        assertEquals(
            listOf(
                Debt("m3", "m1", d("120.00"), "INR"),
                Debt("m4", "m1", d("50.00"), "INR"),
                Debt("m4", "m2", d("100.00"), "INR"),
                Debt("m4", "m3", d("30.00"), "INR"),
            ),
            SharedBalances.pairwiseDebts(listOf(fourWay, threeWay), emptyList())["INR"],
        )
    }

    @Test
    fun `a settlement that overshoots turns the debt around`() {
        // m4 owed m2 100 and paid 150, so m2 now owes m4 50. m4 paid m1 exactly 50, so that pair is gone.
        val debts = SharedBalances.pairwiseDebts(
            listOf(fourWay, threeWay),
            listOf(settlement("m4", "m2", "150.00"), settlement("m4", "m1", "50.00")),
        )
        assertEquals(
            listOf(
                Debt("m2", "m4", d("50.00"), "INR"),
                Debt("m3", "m1", d("120.00"), "INR"),
                Debt("m4", "m3", d("30.00"), "INR"),
            ),
            debts["INR"],
        )
    }

    @Test
    fun `pairwise debts agree with the balances`() {
        val expenses = listOf(fourWay, threeWay) + tangle
        val settlements = listOf(settlement("m4", "m2", "150.00"), settlement("m3", "m1", "20.00"))
        val balances = SharedBalances.balances(expenses, settlements).getValue("INR")
        val debts = SharedBalances.pairwiseDebts(expenses, settlements).getValue("INR")
        assertTrue(settle(balances, debts).values.all { it.signum() == 0 })
        assertEquals(debts, SharedBalances.debts(simplify = false, expenses = expenses, settlements = settlements)["INR"])
    }

    @Test
    fun `pairwise debts keep currencies apart and ignore deleted rows`() {
        val debts = SharedBalances.pairwiseDebts(
            listOf(
                fourWay,
                expense(payers("m1" to "30.00"), shares("m2" to "15.00", "m3" to "15.00"), currency = "USD"),
                expense(payers("m2" to "999.00"), shares("m1" to "999.00"), deletedAt = 5L),
            ),
            listOf(settlement("m2", "m1", "5.00", currency = "USD"), settlement("m3", "m1", "150.00", deletedAt = 6L)),
        )
        assertEquals(listOf("INR", "USD"), debts.keys.toList())
        assertEquals(3, debts.getValue("INR").size)
        assertEquals(
            listOf(Debt("m2", "m1", d("10.00"), "USD"), Debt("m3", "m1", d("15.00"), "USD")),
            debts["USD"],
        )
    }

    @Test
    fun `pairwise debts of a settled group are empty`() {
        val expenses = listOf(expense(payers("m1" to "100.00"), shares("m1" to "50.00", "m2" to "50.00")))
        assertTrue(SharedBalances.pairwiseDebts(expenses, listOf(settlement("m2", "m1", "50.00"))).isEmpty())
        assertTrue(SharedBalances.pairwiseDebts(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `summary across groups is per currency`() {
        val groupA = mapOf("INR" to mapOf("m1" to d("100.00"), "m2" to d("-100.00")))
        val groupB = mapOf(
            "INR" to mapOf("m7" to d("-30.00"), "m8" to d("30.00")),
            "USD" to mapOf("m7" to d("20.00"), "m8" to d("-20.00")),
        )
        val groupC = mapOf("INR" to mapOf("m9" to d("-50.00"), "m10" to d("50.00")), "JPY" to mapOf("m9" to d("0")))
        val summary = SharedBalances.summarize(
            listOf(
                SharedBalances.forMember(groupA, "m1"),
                SharedBalances.forMember(groupB, "m7"),
                SharedBalances.forMember(groupC, "m9"),
            ),
        )
        assertEquals(mapOf("INR" to Money(d("80.00"), "INR")), summary.owe)
        assertEquals(mapOf("INR" to Money(d("100.00"), "INR"), "USD" to Money(d("20.00"), "USD")), summary.owed)
    }

    @Test
    fun `summary of nothing is empty`() {
        val summary = SharedBalances.summarize(emptyList())
        assertTrue(summary.owe.isEmpty())
        assertTrue(summary.owed.isEmpty())
    }
}
