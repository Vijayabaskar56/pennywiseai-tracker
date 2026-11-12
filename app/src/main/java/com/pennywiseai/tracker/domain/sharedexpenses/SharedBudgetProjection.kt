package com.pennywiseai.tracker.domain.sharedexpenses

import java.math.BigDecimal

/**
 * How one Shared expense shows in my budget (docs/shared-expenses.md, "The
 * exact amounts"). [lentSharesTotal] goes on the linked bank transaction and
 * is null when none is linked. [entryAmount] is the separate budget entry for
 * the part of my Share I didn't pay, null when there is none.
 */
data class BudgetProjection(val lentSharesTotal: BigDecimal?, val entryAmount: BigDecimal?)

object SharedBudgetProjection {

    /**
     * With [paid] what I paid (P) and [share] my Share (S): a linked
     * transaction gets `max(P − S, 0)` and the entry is `max(S − P, 0)`;
     * unlinked, the entry is S. A zero entry is null.
     */
    fun project(paid: BigDecimal, share: BigDecimal, linked: Boolean): BudgetProjection {
        if (!linked) return BudgetProjection(lentSharesTotal = null, entryAmount = share.takeIf { it.signum() > 0 })
        val lent = (paid - share).let { if (it.signum() < 0) BigDecimal.ZERO.setScale(it.scale()) else it }
        val entry = (share - paid).takeIf { it.signum() > 0 }
        return BudgetProjection(lentSharesTotal = lent, entryAmount = entry)
    }

    /** [project] for [memberId] on [expense]. A deleted expense projects nothing. */
    fun project(expense: SharedExpense, memberId: String, linked: Boolean): BudgetProjection {
        if (expense.deletedAt != null) return BudgetProjection(null, null)
        return project(paidBy(expense, memberId), shareOf(expense, memberId), linked)
    }

    /** What [memberId] paid on [expense], zero when they aren't a Payer. */
    fun paidBy(expense: SharedExpense, memberId: String): BigDecimal =
        expense.payers.filter { it.memberId == memberId }.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }

    /** [memberId]'s Share of [expense], zero when they have none. */
    fun shareOf(expense: SharedExpense, memberId: String): BigDecimal =
        expense.shares.filter { it.memberId == memberId }.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount }
}
