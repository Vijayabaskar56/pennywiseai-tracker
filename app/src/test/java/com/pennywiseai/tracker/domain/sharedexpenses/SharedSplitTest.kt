package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SharedSplitTest {
    private fun shares(result: SplitResult): List<SharePart> {
        assertTrue("expected success but was $result", result is SplitResult.Success)
        return (result as SplitResult.Success).shares
    }

    private fun error(result: SplitResult): SharedExpenseError {
        assertTrue("expected failure but was $result", result is SplitResult.Failure)
        return (result as SplitResult.Failure).error
    }

    private fun split(amount: String, currency: String, input: SplitInput) = SharedSplit.split(d(amount), currency, input)

    private fun total(parts: List<SharePart>) = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }

    @Test
    fun `equal split gives the leftover unit to the lowest member ID`() {
        val parts = shares(split("1000.00", "INR", SplitInput.Equal(setOf("m3", "m1", "m2"))))
        assertEquals(
            listOf(SharePart("m1", d("333.34")), SharePart("m2", d("333.33")), SharePart("m3", d("333.33"))),
            parts,
        )
    }

    @Test
    fun `equal split hands out two leftover units one each`() {
        val parts = shares(split("100.01", "INR", SplitInput.Equal(setOf("m1", "m2", "m3"))))
        assertEquals(listOf(d("33.34"), d("33.34"), d("33.33")), parts.map { it.amount })
    }

    @Test
    fun `equal split between seven members`() {
        val ids = (1..7).map { "m$it" }.toSet()
        val parts = shares(split("100.00", "INR", SplitInput.Equal(ids)))
        assertEquals(List(4) { d("14.29") } + List(3) { d("14.28") }, parts.map { it.amount })
        assertEquals(ids.sorted(), parts.map { it.memberId })
        assertEquals(d("100.00"), total(parts))
    }

    @Test
    fun `leftover follows member ID order, not input order`() {
        val parts = shares(split("10.00", "INR", SplitInput.Equal(setOf("c", "b", "a"))))
        assertEquals(listOf("a" to d("3.34"), "b" to d("3.33"), "c" to d("3.33")), parts.map { it.memberId to it.amount })
    }

    @Test
    fun `zero-decimal currency splits in whole yen`() {
        val parts = shares(split("1000", "JPY", SplitInput.Equal(setOf("m1", "m2", "m3"))))
        assertEquals(listOf(d("334"), d("333"), d("333")), parts.map { it.amount })
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, error(split("1000.5", "JPY", SplitInput.Equal(setOf("m1")))))
    }

    @Test
    fun `three-decimal currency splits in fils`() {
        val parts = shares(split("10.000", "KWD", SplitInput.Equal(setOf("m1", "m2", "m3"))))
        assertEquals(listOf(d("3.334"), d("3.333"), d("3.333")), parts.map { it.amount })
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, error(split("1.0005", "KWD", SplitInput.Equal(setOf("m1")))))
    }

    @Test
    fun `equal split always adds up`() {
        for (count in 1..10) {
            val ids = (1..count).map { "m$it" }.toSet()
            for (amount in listOf("0.10", "1.00", "99.99", "100.01", "1000.00", "12345.67")) {
                val parts = shares(split(amount, "INR", SplitInput.Equal(ids)))
                assertEquals("$amount / $count", 0, total(parts).compareTo(d(amount)))
                val amounts = parts.map { it.amount }
                assertTrue(amounts.max() - amounts.min() <= d("0.01"))
            }
        }
    }

    @Test
    fun `a member whose part rounds to zero gets no share`() {
        val parts = shares(split("0.02", "INR", SplitInput.Equal(setOf("m1", "m2", "m3"))))
        assertEquals(listOf(SharePart("m1", d("0.01")), SharePart("m2", d("0.01"))), parts)
    }

    @Test
    fun `exact split uses the amounts as given, sorted and padded`() {
        val parts = shares(split("1000.00", "INR", SplitInput.Exact(mapOf("m2" to d("300"), "m1" to d("500"), "m3" to d("200.00")))))
        assertEquals(
            listOf(SharePart("m1", d("500.00")), SharePart("m2", d("300.00")), SharePart("m3", d("200.00"))),
            parts,
        )
    }

    @Test
    fun `exact split errors`() {
        assertEquals(SharedExpenseError.NO_SHARES, error(split("10", "INR", SplitInput.Exact(emptyMap()))))
        assertEquals(
            SharedExpenseError.SHARE_NOT_POSITIVE,
            error(split("10", "INR", SplitInput.Exact(mapOf("m1" to d("10"), "m2" to d("0"))))),
        )
        assertEquals(
            SharedExpenseError.SHARE_TOO_MANY_DECIMALS,
            error(split("10", "INR", SplitInput.Exact(mapOf("m1" to d("9.995"), "m2" to d("0.005"))))),
        )
        assertEquals(
            SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT,
            error(split("10", "INR", SplitInput.Exact(mapOf("m1" to d("6"), "m2" to d("3.99"))))),
        )
    }

    @Test
    fun `percent split rounds down and hands out the leftover by member ID`() {
        val input = SplitInput.Percent(mapOf("m1" to "33.33", "m2" to "33.33", "m3" to "33.34"))
        assertEquals(
            listOf(
                SharePart("m1", d("333.30"), "33.33"),
                SharePart("m2", d("333.30"), "33.33"),
                SharePart("m3", d("333.40"), "33.34"),
            ),
            shares(split("1000.00", "INR", input)),
        )
        // 10.00: exact parts 3.333, 3.333, 3.334; rounded down 3.33 each, one paisa left for m1.
        assertEquals(listOf(d("3.34"), d("3.33"), d("3.33")), shares(split("10.00", "INR", input)).map { it.amount })
    }

    @Test
    fun `percent split 50 30 20 keeps the typed weights`() {
        val parts = shares(split("1000.00", "INR", SplitInput.Percent(mapOf("m1" to "50", "m2" to " 30 ", "m3" to "20.0"))))
        assertEquals(listOf(d("500.00"), d("300.00"), d("200.00")), parts.map { it.amount })
        assertEquals(listOf("50", "30", "20.0"), parts.map { it.weight })
    }

    @Test
    fun `percent split errors`() {
        fun percent(vararg p: Pair<String, String>) = error(split("100", "INR", SplitInput.Percent(mapOf(*p))))
        assertEquals(SharedExpenseError.NO_SHARES, percent())
        assertEquals(SharedExpenseError.PERCENT_NOT_100, percent("m1" to "33.33", "m2" to "33.33", "m3" to "33.33"))
        assertEquals(SharedExpenseError.PERCENT_NOT_100, percent("m1" to "60", "m2" to "50"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "abc", "m2" to "100"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "0", "m2" to "100"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "-10", "m2" to "110"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "", "m2" to "100"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "1e2"))
        assertEquals(SharedExpenseError.PERCENT_INVALID, percent("m1" to "99.9999999", "m2" to "0.0000001"))
    }

    @Test
    fun `shares split 2 to 1 to 1`() {
        val parts = shares(split("1000.00", "INR", SplitInput.Shares(mapOf("m1" to 2, "m2" to 1, "m3" to 1))))
        assertEquals(
            listOf(SharePart("m1", d("500.00"), "2"), SharePart("m2", d("250.00"), "1"), SharePart("m3", d("250.00"), "1")),
            parts,
        )
    }

    @Test
    fun `shares leftover goes by member ID, not by weight`() {
        // Exact parts 33.333 and 66.666: rounded down 33.33 and 66.66, the leftover paisa to m1.
        val parts = shares(split("100.00", "INR", SplitInput.Shares(mapOf("m1" to 1, "m2" to 2))))
        assertEquals(listOf(d("33.34"), d("66.66")), parts.map { it.amount })
    }

    @Test
    fun `shares split errors`() {
        assertEquals(SharedExpenseError.NO_SHARES, error(split("100", "INR", SplitInput.Shares(emptyMap()))))
        assertEquals(SharedExpenseError.SHARE_WEIGHT_INVALID, error(split("100", "INR", SplitInput.Shares(mapOf("m1" to 1, "m2" to 0)))))
        assertEquals(SharedExpenseError.SHARE_WEIGHT_INVALID, error(split("100", "INR", SplitInput.Shares(mapOf("m1" to -1)))))
    }

    @Test
    fun `equal split with nobody fails`() {
        assertEquals(SharedExpenseError.NO_SHARES, error(split("100", "INR", SplitInput.Equal(emptySet()))))
    }

    @Test
    fun `amount and currency are checked first`() {
        val everyone = SplitInput.Equal(setOf("m1", "m2"))
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, error(split("0", "INR", everyone)))
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, error(split("-5", "INR", everyone)))
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, error(split("10.001", "INR", everyone)))
        assertEquals(SharedExpenseError.AMOUNT_TOO_LARGE, error(split("10000000000.00", "INR", everyone)))
        assertEquals(SharedExpenseError.INVALID_CURRENCY, error(split("10", "inr", everyone)))
        assertEquals(SharedExpenseError.INVALID_CURRENCY, error(split("10", "QQQ", everyone)))
    }

    @Test
    fun `input reports its method`() {
        assertEquals(SplitMethod.EQUAL, SplitInput.Equal(emptySet()).method)
        assertEquals(SplitMethod.EXACT, SplitInput.Exact(emptyMap()).method)
        assertEquals(SplitMethod.PERCENT, SplitInput.Percent(emptyMap()).method)
        assertEquals(SplitMethod.SHARES, SplitInput.Shares(emptyMap()).method)
    }
}
