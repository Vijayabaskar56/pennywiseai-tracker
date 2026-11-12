package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.d
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.expense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.payers
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.shares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedValidationTest {
    private val valid = ExpenseDraft(
        description = "Groceries",
        amount = d("1000.00"),
        currency = "INR",
        splitMethod = SplitMethod.EQUAL,
        payers = payers("m1" to "1000.00"),
        shares = shares("m1" to "500.00", "m2" to "500.00"),
    )

    private fun check(draft: ExpenseDraft, memberIds: Set<String>? = null) = SharedValidation.validateExpense(draft, memberIds)

    @Test
    fun `a valid expense passes`() {
        assertNull(check(valid))
        assertNull(check(valid, setOf("m1", "m2")))
        assertNull(check(valid.copy(amount = d("1000")))) // scale doesn't matter
        assertNull(check(valid.copy(payers = payers("m1" to "600", "m2" to "400"))))
        assertNull(check(valid.copy(category = "c".repeat(60), notes = "n".repeat(1000))))
    }

    @Test
    fun `description`() {
        assertEquals(SharedExpenseError.DESCRIPTION_BLANK, check(valid.copy(description = "")))
        assertEquals(SharedExpenseError.DESCRIPTION_BLANK, check(valid.copy(description = "   ")))
        assertNull(check(valid.copy(description = "d".repeat(200))))
        assertNull(check(valid.copy(description = " " + "d".repeat(200) + " ")))
        assertEquals(SharedExpenseError.DESCRIPTION_TOO_LONG, check(valid.copy(description = "d".repeat(201))))
        // Lengths count code points, as the server does: 200 emoji fit although they are 400 UTF-16 chars.
        assertNull(check(valid.copy(description = "\uD83D\uDED2".repeat(200))))
        assertEquals(SharedExpenseError.DESCRIPTION_TOO_LONG, check(valid.copy(description = "\uD83D\uDED2".repeat(201))))
    }

    @Test
    fun `category and notes`() {
        assertEquals(SharedExpenseError.CATEGORY_TOO_LONG, check(valid.copy(category = "c".repeat(61))))
        assertEquals(SharedExpenseError.NOTES_TOO_LONG, check(valid.copy(notes = "n".repeat(1001))))
        // The server keeps notes as typed, spaces included.
        assertEquals(SharedExpenseError.NOTES_TOO_LONG, check(valid.copy(notes = "n".repeat(1000) + " ")))
        assertNull(check(valid.copy(category = " " + "c".repeat(60) + " ")))
    }

    @Test
    fun `currency`() {
        assertEquals(SharedExpenseError.INVALID_CURRENCY, check(valid.copy(currency = "inr")))
        assertEquals(SharedExpenseError.INVALID_CURRENCY, check(valid.copy(currency = "QQQ")))
        assertEquals(SharedExpenseError.INVALID_CURRENCY, check(valid.copy(currency = "")))
    }

    @Test
    fun `amount`() {
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, check(valid.copy(amount = d("0"))))
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, check(valid.copy(amount = d("-1000"))))
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, check(valid.copy(amount = d("1000.001"))))
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, check(valid.copy(currency = "JPY", amount = d("1000.5"))))
        // Trailing zeros are fine: 1000.000 is a whole number of paise.
        assertNull(check(valid.copy(amount = d("1000.000"))))
        // Below 10^12 minor units.
        assertEquals(SharedExpenseError.AMOUNT_TOO_LARGE, check(valid.copy(amount = d("10000000000.00"))))
        val largest = d("9999999999.99")
        assertNull(
            check(
                valid.copy(
                    amount = largest,
                    payers = listOf(PayerPart("m1", largest)),
                    shares = listOf(SharePart("m1", largest)),
                ),
            ),
        )
    }

    @Test
    fun `payers`() {
        assertEquals(SharedExpenseError.NO_PAYERS, check(valid.copy(payers = emptyList())))
        assertEquals(SharedExpenseError.PAYER_NOT_POSITIVE, check(valid.copy(payers = payers("m1" to "1000", "m2" to "0"))))
        assertEquals(SharedExpenseError.PAYER_NOT_POSITIVE, check(valid.copy(payers = payers("m1" to "1100", "m2" to "-100"))))
        assertEquals(
            SharedExpenseError.PAYER_TOO_MANY_DECIMALS,
            check(valid.copy(payers = payers("m1" to "999.995", "m2" to "0.005"))),
        )
        assertEquals(SharedExpenseError.DUPLICATE_PAYER, check(valid.copy(payers = payers("m1" to "500", "m1" to "500"))))
        assertEquals(SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT, check(valid.copy(payers = payers("m1" to "999.99"))))
        assertEquals(SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT, check(valid.copy(payers = payers("m1" to "600", "m2" to "500"))))
    }

    @Test
    fun `shares`() {
        assertEquals(SharedExpenseError.NO_SHARES, check(valid.copy(shares = emptyList())))
        assertEquals(SharedExpenseError.SHARE_NOT_POSITIVE, check(valid.copy(shares = shares("m1" to "1000", "m2" to "0"))))
        assertEquals(
            SharedExpenseError.SHARE_TOO_MANY_DECIMALS,
            check(valid.copy(shares = shares("m1" to "999.995", "m2" to "0.005"))),
        )
        assertEquals(SharedExpenseError.DUPLICATE_SHARE, check(valid.copy(shares = shares("m2" to "500", "m2" to "500"))))
        assertEquals(SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT, check(valid.copy(shares = shares("m1" to "500", "m2" to "499.99"))))
    }

    @Test
    fun `percent weights must be numbers above zero totalling 100`() {
        val percent = valid.copy(
            splitMethod = SplitMethod.PERCENT,
            shares = listOf(SharePart("m1", d("500.00"), "50"), SharePart("m2", d("500.00"), "50.00")),
        )
        assertNull(check(percent))
        assertEquals(
            SharedExpenseError.PERCENT_NOT_100,
            check(percent.copy(shares = listOf(SharePart("m1", d("500.00"), "50"), SharePart("m2", d("500.00"), "49.99")))),
        )
        assertEquals(
            SharedExpenseError.PERCENT_INVALID,
            check(percent.copy(shares = listOf(SharePart("m1", d("500.00"), "50"), SharePart("m2", d("500.00"), null)))),
        )
        assertEquals(
            SharedExpenseError.PERCENT_INVALID,
            check(percent.copy(shares = listOf(SharePart("m1", d("500.00"), "100"), SharePart("m2", d("500.00"), "0")))),
        )
        // Only the server's weight format: digits, up to six decimals, nothing else.
        for (bad in listOf("+50", " 50", "5e1", "50.0000001")) {
            assertEquals(
                "weight $bad",
                SharedExpenseError.PERCENT_INVALID,
                check(percent.copy(shares = listOf(SharePart("m1", d("500.00"), bad), SharePart("m2", d("500.00"), "50")))),
            )
        }
    }

    @Test
    fun `share weights must be whole numbers above zero`() {
        val weighted = valid.copy(
            splitMethod = SplitMethod.SHARES,
            shares = listOf(SharePart("m1", d("500.00"), "1"), SharePart("m2", d("500.00"), "1")),
        )
        assertNull(check(weighted))
        for (bad in listOf(null, "", "x", "0", "-1", "+1", "1.5")) {
            assertEquals(
                "weight $bad",
                SharedExpenseError.SHARE_WEIGHT_INVALID,
                check(weighted.copy(shares = listOf(SharePart("m1", d("500.00"), "1"), SharePart("m2", d("500.00"), bad)))),
            )
        }
    }

    @Test
    fun `equal and exact shares carry no weight`() {
        val weighted = listOf(SharePart("m1", d("500.00"), "1"), SharePart("m2", d("500.00")))
        assertEquals(SharedExpenseError.WEIGHT_NOT_ALLOWED, check(valid.copy(shares = weighted)))
        assertEquals(SharedExpenseError.WEIGHT_NOT_ALLOWED, check(valid.copy(splitMethod = SplitMethod.EXACT, shares = weighted)))
    }

    @Test
    fun `every member must belong to the group when the members are given`() {
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, check(valid, setOf("m1")))
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, check(valid, setOf("m2")))
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, check(valid, emptySet()))
    }

    @Test
    fun `a split result passes validation`() {
        val result = SharedSplit.split(d("1000.00"), "INR", SplitInput.Percent(mapOf("m1" to "33.33", "m2" to "33.33", "m3" to "33.34")))
        val draft = valid.copy(splitMethod = SplitMethod.PERCENT, shares = (result as SplitResult.Success).shares)
        assertNull(check(draft, setOf("m1", "m2", "m3")))
    }

    @Test
    fun `stored expenses and templates convert to drafts`() {
        val stored = expense(payers("m1" to "100.00"), shares("m1" to "50.00", "m2" to "50.00"))
        assertNull(check(stored.toDraft()))
        assertEquals(stored.payers, stored.toDraft().payers)
        val template = ExpenseTemplate(
            description = "Rent",
            amount = d("300.00"),
            currency = "INR",
            splitMethod = SplitMethod.EQUAL,
            payers = payers("m1" to "300.00"),
            shares = shares("m1" to "100.00", "m2" to "100.00", "m3" to "100.00"),
        )
        assertNull(check(template.toDraft()))
        assertEquals("Rent", template.toDraft().description)
    }

    @Test
    fun `errors name the field the server would`() {
        assertEquals("description", SharedExpenseError.DESCRIPTION_BLANK.field)
        assertEquals("amount", SharedExpenseError.AMOUNT_TOO_LARGE.field)
        assertEquals("payers", SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT.field)
        assertEquals("shares", SharedExpenseError.PERCENT_NOT_100.field)
        assertEquals("currency", SharedExpenseError.INVALID_CURRENCY.field)
    }

    private val settlement = SettlementDraft(fromMemberId = "m1", toMemberId = "m2", amount = d("500.00"), currency = "INR")

    @Test
    fun `settlement validation`() {
        assertNull(SharedValidation.validateSettlement(settlement))
        assertNull(SharedValidation.validateSettlement(settlement, setOf("m1", "m2")))
        assertEquals(SharedExpenseError.SETTLEMENT_SAME_MEMBER, SharedValidation.validateSettlement(settlement.copy(toMemberId = "m1")))
        assertEquals(SharedExpenseError.AMOUNT_NOT_POSITIVE, SharedValidation.validateSettlement(settlement.copy(amount = d("0"))))
        assertEquals(SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS, SharedValidation.validateSettlement(settlement.copy(amount = d("0.001"))))
        assertEquals(
            SharedExpenseError.AMOUNT_TOO_LARGE,
            SharedValidation.validateSettlement(settlement.copy(amount = d("1000000000000"), currency = "JPY")),
        )
        assertEquals(SharedExpenseError.INVALID_CURRENCY, SharedValidation.validateSettlement(settlement.copy(currency = "usd")))
        assertEquals(SharedExpenseError.NOTES_TOO_LONG, SharedValidation.validateSettlement(settlement.copy(notes = "n".repeat(1001))))
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, SharedValidation.validateSettlement(settlement, setOf("m1")))
        assertEquals(SharedExpenseError.UNKNOWN_MEMBER, SharedValidation.validateSettlement(settlement, setOf("m2")))
    }

    @Test
    fun `group and display names`() {
        assertNull(SharedValidation.validateGroupName("Flat"))
        assertNull(SharedValidation.validateGroupName("g".repeat(60)))
        assertEquals(SharedExpenseError.GROUP_NAME_BLANK, SharedValidation.validateGroupName(" "))
        assertEquals(SharedExpenseError.GROUP_NAME_TOO_LONG, SharedValidation.validateGroupName("g".repeat(61)))
        assertNull(SharedValidation.validateGroupName("  " + "g".repeat(60) + "  "))
        assertNull(SharedValidation.validateDisplayName("Member A"))
        assertNull(SharedValidation.validateDisplayName("n".repeat(40)))
        assertEquals(SharedExpenseError.DISPLAY_NAME_BLANK, SharedValidation.validateDisplayName(""))
        assertEquals(SharedExpenseError.DISPLAY_NAME_TOO_LONG, SharedValidation.validateDisplayName("n".repeat(41)))
    }
}
