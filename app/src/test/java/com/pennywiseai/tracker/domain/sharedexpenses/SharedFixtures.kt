package com.pennywiseai.tracker.domain.sharedexpenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.math.BigDecimal
import java.time.LocalDate

/** Builders for Shared expense tests. IDs and names are generic placeholders. */
internal object SharedFixtures {
    private var nextId = 0

    fun d(value: String) = BigDecimal(value)

    fun payers(vararg parts: Pair<String, String>) = parts.map { (id, amount) -> PayerPart(id, d(amount)) }

    fun shares(vararg parts: Pair<String, String>) = parts.map { (id, amount) -> SharePart(id, d(amount)) }

    fun expense(
        payers: List<PayerPart>,
        shares: List<SharePart>,
        currency: String = "INR",
        deletedAt: Long? = null,
        splitMethod: SplitMethod = SplitMethod.EXACT,
    ) = SharedExpense(
        id = "e${nextId++}",
        groupId = "g1",
        description = "Expense",
        amount = payers.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount },
        currency = currency,
        date = LocalDate.of(2026, 11, 10),
        splitMethod = splitMethod,
        payers = payers,
        shares = shares,
        createdBy = payers.first().memberId,
        updatedBy = payers.first().memberId,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = deletedAt,
        version = 1,
        rev = 1,
    )

    fun settlement(from: String, to: String, amount: String, currency: String = "INR", deletedAt: Long? = null) =
        SharedSettlement(
            id = "s${nextId++}",
            groupId = "g1",
            fromMemberId = from,
            toMemberId = to,
            amount = d(amount),
            currency = currency,
            date = LocalDate.of(2026, 12, 5),
            createdBy = from,
            updatedBy = from,
            createdAt = 0,
            updatedAt = 0,
            deletedAt = deletedAt,
            version = 1,
            rev = 2,
        )

    /** Equal by value, whatever the scale. */
    fun assertAmount(expected: String?, actual: BigDecimal?) {
        if (expected == null) {
            assertNull(actual)
        } else {
            assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual))
        }
    }
}
