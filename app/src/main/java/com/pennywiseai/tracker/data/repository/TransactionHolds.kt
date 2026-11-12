package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.data.database.entity.TransactionEntity

/**
 * How a feature outside the main database holds a transaction. The shared
 * write paths ([TransactionRepository.updateTransaction], Split bill) ask
 * before writing, so an edit, a rule, a re-parse or a bulk update keeps what
 * that feature set on the row.
 */
enum class TransactionHold {
    /** Linked to a Shared expense: its `lent_shares_total` belongs to the Shared expense. */
    SHARED_EXPENSE,

    /** Linked to a Settlement: it stays out of spending and income (`excluded_from_analytics`). */
    SHARED_SETTLEMENT,

    /** A budget entry made for a Shared expense: its amount, currency, type and date follow that expense. */
    SHARED_ENTRY,
}

fun interface TransactionHolds {
    /** How [transaction] is held, or null when nothing outside the main database holds it. */
    suspend fun holdOf(transaction: TransactionEntity): TransactionHold?

    companion object {
        val NONE = TransactionHolds { null }
    }
}
