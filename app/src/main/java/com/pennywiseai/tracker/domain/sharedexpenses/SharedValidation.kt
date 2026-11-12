package com.pennywiseai.tracker.domain.sharedexpenses

import java.math.BigDecimal

/** A Shared expense as the form holds it before saving. */
data class ExpenseDraft(
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val splitMethod: SplitMethod,
    val payers: List<PayerPart>,
    val shares: List<SharePart>,
    val category: String? = null,
    val notes: String? = null,
)

/** A Settlement as the settle-up form holds it before saving. */
data class SettlementDraft(
    val fromMemberId: String,
    val toMemberId: String,
    val amount: BigDecimal,
    val currency: String,
    val notes: String? = null,
)

fun SharedExpense.toDraft(): ExpenseDraft =
    ExpenseDraft(description, amount, currency, splitMethod, payers, shares, category, notes)

fun ExpenseTemplate.toDraft(): ExpenseDraft =
    ExpenseDraft(description, amount, currency, splitMethod, payers, shares, category, notes)

/**
 * The phone's copy of the server's validation (docs/shared-expenses-api.md),
 * checked before anything is saved or queued. Each function returns the first
 * problem found, or null when the input can be saved.
 */
object SharedValidation {

    /**
     * Checks [draft]. With [memberIds], every payer and share must be one of
     * them; pass the group's active members plus any removed member the stored
     * expense already names, as the server accepts those.
     */
    fun validateExpense(draft: ExpenseDraft, memberIds: Set<String>? = null): SharedExpenseError? {
        val description = draft.description.trim()
        if (description.isEmpty()) return SharedExpenseError.DESCRIPTION_BLANK
        if (description.textLength() > SharedLimits.MAX_DESCRIPTION) return SharedExpenseError.DESCRIPTION_TOO_LONG
        if ((draft.category?.trim()?.textLength() ?: 0) > SharedLimits.MAX_CATEGORY) return SharedExpenseError.CATEGORY_TOO_LONG
        if ((draft.notes?.textLength() ?: 0) > SharedLimits.MAX_NOTES) return SharedExpenseError.NOTES_TOO_LONG
        val digits = SharedMoney.strictFractionDigits(draft.currency) ?: return SharedExpenseError.INVALID_CURRENCY
        amountError(draft.amount, digits)?.let { return it }

        val payers = draft.payers
        when {
            payers.isEmpty() -> return SharedExpenseError.NO_PAYERS
            payers.any { it.amount.signum() <= 0 } -> return SharedExpenseError.PAYER_NOT_POSITIVE
            payers.any { !SharedMoney.fitsMinorUnits(it.amount, digits) } -> return SharedExpenseError.PAYER_TOO_MANY_DECIMALS
            payers.map { it.memberId }.toSet().size != payers.size -> return SharedExpenseError.DUPLICATE_PAYER
            payers.sumOf { it.amount }.compareTo(draft.amount) != 0 -> return SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT
        }

        val shares = draft.shares
        when {
            shares.isEmpty() -> return SharedExpenseError.NO_SHARES
            shares.any { it.amount.signum() <= 0 } -> return SharedExpenseError.SHARE_NOT_POSITIVE
            shares.any { !SharedMoney.fitsMinorUnits(it.amount, digits) } -> return SharedExpenseError.SHARE_TOO_MANY_DECIMALS
            shares.map { it.memberId }.toSet().size != shares.size -> return SharedExpenseError.DUPLICATE_SHARE
            shares.sumOf { it.amount }.compareTo(draft.amount) != 0 -> return SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT
        }

        when (draft.splitMethod) {
            SplitMethod.PERCENT -> {
                val percents = shares.map { SharedSplit.parsePercent(it.weight) }
                if (percents.any { it == null }) return SharedExpenseError.PERCENT_INVALID
                if (percents.sumOf { it!! }.compareTo(BigDecimal(100)) != 0) return SharedExpenseError.PERCENT_NOT_100
            }
            SplitMethod.SHARES ->
                if (shares.any { SharedSplit.parseShareWeight(it.weight) == null }) return SharedExpenseError.SHARE_WEIGHT_INVALID
            SplitMethod.EQUAL, SplitMethod.EXACT ->
                if (shares.any { it.weight != null }) return SharedExpenseError.WEIGHT_NOT_ALLOWED
        }

        if (memberIds != null && (payers.any { it.memberId !in memberIds } || shares.any { it.memberId !in memberIds })) {
            return SharedExpenseError.UNKNOWN_MEMBER
        }
        return null
    }

    /** Checks [draft]; [memberIds] as in [validateExpense]. */
    fun validateSettlement(draft: SettlementDraft, memberIds: Set<String>? = null): SharedExpenseError? {
        if (draft.fromMemberId == draft.toMemberId) return SharedExpenseError.SETTLEMENT_SAME_MEMBER
        if ((draft.notes?.textLength() ?: 0) > SharedLimits.MAX_NOTES) return SharedExpenseError.NOTES_TOO_LONG
        val digits = SharedMoney.strictFractionDigits(draft.currency) ?: return SharedExpenseError.INVALID_CURRENCY
        amountError(draft.amount, digits)?.let { return it }
        if (memberIds != null && (draft.fromMemberId !in memberIds || draft.toMemberId !in memberIds)) {
            return SharedExpenseError.UNKNOWN_MEMBER
        }
        return null
    }

    /** A Shared group's name: 1 to 60 characters after trimming. */
    fun validateGroupName(name: String): SharedExpenseError? = when {
        name.isBlank() -> SharedExpenseError.GROUP_NAME_BLANK
        name.trim().textLength() > SharedLimits.MAX_GROUP_NAME -> SharedExpenseError.GROUP_NAME_TOO_LONG
        else -> null
    }

    /** A member's display name: 1 to 40 characters after trimming. */
    fun validateDisplayName(name: String): SharedExpenseError? = when {
        name.isBlank() -> SharedExpenseError.DISPLAY_NAME_BLANK
        name.trim().textLength() > SharedLimits.MAX_DISPLAY_NAME -> SharedExpenseError.DISPLAY_NAME_TOO_LONG
        else -> null
    }

    // Length in code points, as the server counts it: an emoji is one character.
    private fun String.textLength(): Int = codePointCount(0, length)

    /** An expense or Settlement amount in a currency with [digits] decimals. */
    internal fun amountError(amount: BigDecimal, digits: Int): SharedExpenseError? = when {
        amount.signum() <= 0 -> SharedExpenseError.AMOUNT_NOT_POSITIVE
        !SharedMoney.fitsMinorUnits(amount, digits) -> SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS
        SharedMoney.toMinor(amount, digits) >= SharedLimits.MAX_MINOR_UNITS_EXCLUSIVE -> SharedExpenseError.AMOUNT_TOO_LARGE
        else -> null
    }
}
