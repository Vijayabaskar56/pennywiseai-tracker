package com.pennywiseai.tracker.domain.sharedexpenses

import java.math.BigDecimal
import java.time.LocalDate

// Domain models for Shared expenses (docs/shared-expenses.md). They mirror the
// wire objects in docs/shared-expenses-api.md; the data layer has its own wire
// and Room types and maps to these. Amounts are in the row's one currency,
// times are epoch milliseconds, and a non-null deletedAt marks a soft delete.

/** A Shared group. [myMemberId] is the seat this phone holds, null when unknown. */
data class SharedGroup(
    val id: String,
    val name: String,
    val defaultCurrency: String,
    val simplifyDebts: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val rev: Long,
    val myMemberId: String? = null,
)

/** One seat in a Shared group. [joined] is true once a phone holds it. */
data class SharedMember(
    val id: String,
    val groupId: String,
    val displayName: String,
    val joined: Boolean,
    val removedAt: Long? = null,
    val rev: Long,
)

enum class SplitMethod { EQUAL, EXACT, PERCENT, SHARES }

/** What one Payer paid towards a Shared expense. */
data class PayerPart(val memberId: String, val amount: BigDecimal)

/**
 * One member's Share of a Shared expense. [weight] is what was typed for
 * PERCENT ("33.33") or SHARES ("2"), and null for EQUAL and EXACT.
 */
data class SharePart(val memberId: String, val amount: BigDecimal, val weight: String? = null)

/** A Shared expense. [pending] means it is saved on this phone and not yet on the server. */
data class SharedExpense(
    val id: String,
    val groupId: String,
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val category: String? = null,
    val notes: String? = null,
    val splitMethod: SplitMethod,
    val payers: List<PayerPart>,
    val shares: List<SharePart>,
    val recurringId: String? = null,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int,
    val rev: Long,
    val pending: Boolean = false,
)

/** [fromMemberId] paid [toMemberId]. [pending] as on [SharedExpense]. */
data class SharedSettlement(
    val id: String,
    val groupId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val notes: String? = null,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int,
    val rev: Long,
    val pending: Boolean = false,
)

enum class RecurringInterval { WEEKLY, MONTHLY, YEARLY }

/** The fields of a Shared expense that a recurring expense repeats. */
data class ExpenseTemplate(
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val category: String? = null,
    val notes: String? = null,
    val splitMethod: SplitMethod,
    val payers: List<PayerPart>,
    val shares: List<SharePart>,
)

/** A recurring expense. MONTHLY and YEARLY keep [anchorDay]; see [RecurringDates]. */
data class SharedRecurring(
    val id: String,
    val groupId: String,
    val template: ExpenseTemplate,
    val interval: RecurringInterval,
    val anchorDay: Int,
    val nextDate: LocalDate,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Int,
    val rev: Long,
)

enum class ActivityType {
    GROUP_CREATED,
    GROUP_DELETED,
    GROUP_RENAMED,
    GROUP_SETTINGS_CHANGED,
    MEMBER_ADDED,
    MEMBER_JOINED,
    MEMBER_REMOVED,
    MEMBER_RENAMED,
    EXPENSE_ADDED,
    EXPENSE_EDITED,
    EXPENSE_DELETED,
    EXPENSE_RESTORED,
    SETTLEMENT_ADDED,
    SETTLEMENT_EDITED,
    SETTLEMENT_DELETED,
    RECURRING_ADDED,
    RECURRING_EDITED,
    RECURRING_STOPPED,
}

/** One row of a group's activity feed. [actorMemberId] is null when the server acted. */
data class SharedActivity(
    val seq: Long,
    val groupId: String,
    val actorMemberId: String?,
    val type: ActivityType,
    val entityId: String,
    val summary: Map<String, String>,
    val createdAt: Long,
)

/** [fromMemberId] owes [toMemberId] [amount] in [currency]. */
data class Debt(val fromMemberId: String, val toMemberId: String, val amount: BigDecimal, val currency: String)
