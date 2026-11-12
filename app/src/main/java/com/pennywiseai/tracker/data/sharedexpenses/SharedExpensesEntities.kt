package com.pennywiseai.tracker.data.sharedexpenses

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Tables of shared_expenses.db. Money is text (BigDecimal.toPlainString) next to
// its currency; dates are ISO text; times are epoch milliseconds. No foreign keys:
// a changes page can bring children before their group row, and removing a group
// deletes from every table explicitly.

@Entity(tableName = "shared_groups")
data class SharedGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "default_currency") val defaultCurrency: String,
    @ColumnInfo(name = "simplify_debts") val simplifyDebts: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** The group row's own revision on the server. */
    val rev: Long,
    /** The sync cursor: the `changes` revision this phone has applied. */
    @ColumnInfo(name = "sync_rev") val syncRev: Long,
    @ColumnInfo(name = "my_member_id") val myMemberId: String?,
)

@Entity(tableName = "shared_members", indices = [Index("group_id")])
data class SharedMemberEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val joined: Boolean,
    @ColumnInfo(name = "removed_at") val removedAt: Long?,
    val rev: Long,
)

@Entity(tableName = "shared_expenses", indices = [Index("group_id"), Index("date")])
data class SharedExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val description: String,
    val amount: String,
    val currency: String,
    val date: String,
    val category: String?,
    val notes: String?,
    @ColumnInfo(name = "split_method") val splitMethod: String,
    @ColumnInfo(name = "recurring_id") val recurringId: String?,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val version: Int,
    val rev: Long,
    /** Saved here and not yet confirmed by the server. */
    val pending: Boolean,
)

@Entity(tableName = "shared_expense_payers", primaryKeys = ["expense_id", "member_id"])
data class SharedExpensePayerEntity(
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
    val amount: String,
    val position: Int,
)

@Entity(tableName = "shared_expense_shares", primaryKeys = ["expense_id", "member_id"])
data class SharedExpenseShareEntity(
    @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
    val amount: String,
    val weight: String?,
    val position: Int,
)

@Entity(tableName = "shared_settlements", indices = [Index("group_id")])
data class SharedSettlementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "from_member_id") val fromMemberId: String,
    @ColumnInfo(name = "to_member_id") val toMemberId: String,
    val amount: String,
    val currency: String,
    val date: String,
    val notes: String?,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val version: Int,
    val rev: Long,
    val pending: Boolean,
)

/** A recurring expense. The template's payers and shares are JSON text (amounts as decimal strings). */
@Entity(tableName = "shared_recurring", indices = [Index("group_id")])
data class SharedRecurringEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val description: String,
    val amount: String,
    val currency: String,
    val category: String?,
    val notes: String?,
    @ColumnInfo(name = "split_method") val splitMethod: String,
    @ColumnInfo(name = "payers_json") val payersJson: String,
    @ColumnInfo(name = "shares_json") val sharesJson: String,
    val interval: String,
    @ColumnInfo(name = "anchor_day") val anchorDay: Int,
    @ColumnInfo(name = "next_date") val nextDate: String,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    val version: Int,
    val rev: Long,
    val pending: Boolean,
)

/** One activity row. [type] is a known ActivityType name; unknown types are never stored. */
@Entity(tableName = "shared_activity", primaryKeys = ["group_id", "seq"], indices = [Index("created_at")])
data class SharedActivityEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    val seq: Long,
    @ColumnInfo(name = "actor_member_id") val actorMemberId: String?,
    val type: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "summary_json") val summaryJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A queued change; see [SharedOutboxItem]. */
@Entity(
    tableName = "shared_outbox",
    indices = [Index(value = ["entity_type", "entity_id"], unique = true), Index("group_id")],
)
data class SharedOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: String,
    @ColumnInfo(name = "base_version") val baseVersion: Int,
    val revision: Int,
    val attempted: Boolean,
    @ColumnInfo(name = "restore_first") val restoreFirst: Boolean,
    val status: String,
    @ColumnInfo(name = "server_json") val serverJson: String?,
    @ColumnInfo(name = "error_status") val errorStatus: Int?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "error_field") val errorField: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Which local transaction is linked to which Shared expense or Settlement (SharedBudgetLink). */
@Entity(
    tableName = "shared_links",
    indices = [Index(value = ["transaction_id"], unique = true), Index(value = ["entity_type", "entity_id"]), Index("group_id")],
)
data class SharedLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: String,
    /** EXPENSE or SETTLEMENT. */
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "transaction_hash") val transactionHash: String,
    /**
     * What unlinking puts back. For a Settlement link, the transaction's
     * `excluded_from_analytics` before linking ([SharedLink.ORIGINAL_EXCLUDED]
     * or [SharedLink.ORIGINAL_COUNTED]); null for an expense link, whose
     * `lent_shares_total` was null before linking.
     */
    @ColumnInfo(name = "original_type") val originalType: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * The budget entry this phone made for a Shared expense (SharedBudgetLink): the
 * values it last wrote. A row with no live `shared:<expense id>` transaction is an
 * entry the user deleted, which stays deleted.
 */
@Entity(tableName = "shared_projections", indices = [Index("group_id")])
data class SharedProjectionEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "transaction_hash") val transactionHash: String,
    val amount: String,
    val currency: String,
    val date: String,
    val category: String?,
    val description: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
