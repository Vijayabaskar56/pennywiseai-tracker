package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement

enum class SharedOutboxStatus {
    /** Waiting to be sent. */
    PENDING,

    /** The server answered `409 conflict`; the user picks "keep mine" or "use theirs". */
    CONFLICT,

    /** Refused for good (403, 404, 422…); the user discards it. */
    FAILED,
}

/**
 * One queued change, at most one per row. The row itself (the user's version)
 * lives in its own table, marked pending; this says what to send.
 *
 * - [baseVersion] is the server version the change is based on (0 = create).
 * - [revision] goes up with every local change to the item, so a send that
 *   finishes after the user changed the row again does not drop that change.
 * - [attempted] is true once the change may have reached the server.
 * - [restoreFirst]: a deleted expense was restored and then edited, so the
 *   server needs `restore` before the `PUT`.
 * - [serverJson] is the latest known server copy of the row (wire JSON), used
 *   for "use theirs", for discarding, and as the conflict's `current`.
 */
data class SharedOutboxItem(
    val id: Long = 0,
    val groupId: String,
    val entityType: SharedEntityType,
    val entityId: String,
    val operation: SharedOperation,
    val baseVersion: Int,
    val revision: Int = 0,
    val attempted: Boolean = false,
    val restoreFirst: Boolean = false,
    val status: SharedOutboxStatus = SharedOutboxStatus.PENDING,
    val serverJson: String? = null,
    val errorStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val errorField: String? = null,
    val createdAt: Long,
) {
    /** The stored refusal, for a [SharedOutboxStatus.FAILED] item. */
    val error: SharedError?
        get() = errorStatus?.let { SharedError.Server(it, SharedServerCode.fromWire(errorCode, it), errorMessage.orEmpty(), errorField) }

    fun withError(error: SharedError?): SharedOutboxItem = when (error) {
        is SharedError.Server -> copy(errorStatus = error.status, errorCode = error.code.wire, errorMessage = error.message, errorField = error.field)
        null -> copy(errorStatus = null, errorCode = null, errorMessage = null, errorField = null)
        else -> copy(errorStatus = 0, errorCode = SharedServerCode.UNKNOWN.wire, errorMessage = error.toString(), errorField = null)
    }
}

/** The local copy of every Shared group, as the sync manager sees it. Room in the app, a map in tests. */
interface SharedLocalStore {
    /** Runs [block] as one database transaction. */
    suspend fun <R> transaction(block: suspend () -> R): R

    suspend fun groupIds(): List<String>
    suspend fun group(id: String): SharedGroup?

    /** The revision this phone has pulled the group up to; null when the group is not stored. */
    suspend fun groupCursor(id: String): Long?

    /** Inserts or updates the group, keeping its cursor (0 for a new group). */
    suspend fun upsertGroup(group: SharedGroup)
    suspend fun setGroupCursor(id: String, rev: Long, myMemberId: String)

    /** Deletes the group and every row of it, its outbox items included. */
    suspend fun removeGroup(id: String)

    /** Deletes every group, row and outbox item (a new server address). */
    suspend fun clearAll()

    suspend fun members(groupId: String): List<SharedMember>
    suspend fun upsertMember(member: SharedMember)

    suspend fun expense(id: String): SharedExpense?
    suspend fun upsertExpense(expense: SharedExpense)
    suspend fun deleteExpense(id: String)

    suspend fun settlement(id: String): SharedSettlement?
    suspend fun upsertSettlement(settlement: SharedSettlement)
    suspend fun deleteSettlement(id: String)

    suspend fun recurring(id: String): SharedRecurring?
    suspend fun upsertRecurring(recurring: SharedRecurring)
    suspend fun deleteRecurring(id: String)

    /** Stores an activity row; one that is already stored is left as it is. */
    suspend fun insertActivity(activity: SharedActivity)

    /** Every outbox item, oldest first. */
    suspend fun outbox(): List<SharedOutboxItem>
    suspend fun outboxItem(id: Long): SharedOutboxItem?
    suspend fun outboxFor(type: SharedEntityType, entityId: String): SharedOutboxItem?
    suspend fun insertOutbox(item: SharedOutboxItem): Long
    suspend fun updateOutbox(item: SharedOutboxItem)
    suspend fun deleteOutbox(id: Long)
}

/** The stored row of [type] and [id], whichever table it is in. */
suspend fun SharedLocalStore.entity(type: SharedEntityType, id: String): SharedEntity? = when (type) {
    SharedEntityType.EXPENSE -> expense(id)?.let(SharedEntity::Expense)
    SharedEntityType.SETTLEMENT -> settlement(id)?.let(SharedEntity::Settlement)
    SharedEntityType.RECURRING -> recurring(id)?.let(SharedEntity::Recurring)
}

/** Writes [entity] to its table with [pending] set as given. */
suspend fun SharedLocalStore.writeEntity(entity: SharedEntity, pending: Boolean) = when (entity) {
    is SharedEntity.Expense -> upsertExpense(entity.value.copy(pending = pending))
    is SharedEntity.Settlement -> upsertSettlement(entity.value.copy(pending = pending))
    is SharedEntity.Recurring -> upsertRecurring(entity.value.copy(pending = pending))
}

suspend fun SharedLocalStore.deleteEntity(type: SharedEntityType, id: String) = when (type) {
    SharedEntityType.EXPENSE -> deleteExpense(id)
    SharedEntityType.SETTLEMENT -> deleteSettlement(id)
    SharedEntityType.RECURRING -> deleteRecurring(id)
}
