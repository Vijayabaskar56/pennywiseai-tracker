package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.database.withTransaction
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import javax.inject.Inject
import javax.inject.Singleton

/** [SharedLocalStore] on shared_expenses.db. */
@Singleton
class RoomSharedLocalStore @Inject constructor(private val db: SharedExpensesDatabase) : SharedLocalStore {
    private val groups = db.groupDao()
    private val members = db.memberDao()
    private val expenses = db.expenseDao()
    private val settlements = db.settlementDao()
    private val recurrings = db.recurringDao()
    private val activity = db.activityDao()
    private val outbox = db.outboxDao()

    override suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction(block)

    override suspend fun groupIds(): List<String> = groups.ids()

    override suspend fun group(id: String): SharedGroup? = groups.byId(id)?.toDomain()

    override suspend fun groupCursor(id: String): Long? = groups.byId(id)?.syncRev

    override suspend fun upsertGroup(group: SharedGroup) {
        val stored = groups.byId(group.id)
        groups.upsert(group.copy(myMemberId = group.myMemberId ?: stored?.myMemberId).toEntity(syncRev = stored?.syncRev ?: 0))
    }

    override suspend fun setGroupCursor(id: String, rev: Long, myMemberId: String) = groups.setCursor(id, rev, myMemberId)

    override suspend fun removeGroup(id: String) {
        outbox.deleteForGroup(id)
        activity.deleteForGroup(id)
        recurrings.deleteForGroup(id)
        settlements.deleteForGroup(id)
        expenses.deletePayersForGroup(id)
        expenses.deleteSharesForGroup(id)
        expenses.deleteForGroup(id)
        members.deleteForGroup(id)
        groups.delete(id)
    }

    override suspend fun clearAll() {
        outbox.deleteAll()
        activity.deleteAll()
        recurrings.deleteAll()
        settlements.deleteAll()
        expenses.deleteAllPayers()
        expenses.deleteAllShares()
        expenses.deleteAll()
        members.deleteAll()
        groups.deleteAll()
    }

    override suspend fun members(groupId: String): List<SharedMember> = members.forGroup(groupId).map { it.toDomain() }

    override suspend fun upsertMember(member: SharedMember) = members.upsert(member.toEntity())

    override suspend fun expense(id: String): SharedExpense? =
        expenses.byId(id)?.toDomain(expenses.payers(id), expenses.shares(id))

    override suspend fun upsertExpense(expense: SharedExpense) {
        expenses.upsert(expense.toEntity())
        expenses.deletePayers(expense.id)
        expenses.deleteShares(expense.id)
        expenses.insertPayers(expense.payerEntities())
        expenses.insertShares(expense.shareEntities())
    }

    override suspend fun deleteExpense(id: String) {
        expenses.deletePayers(id)
        expenses.deleteShares(id)
        expenses.delete(id)
    }

    override suspend fun settlement(id: String): SharedSettlement? = settlements.byId(id)?.toDomain()

    override suspend fun upsertSettlement(settlement: SharedSettlement) = settlements.upsert(settlement.toEntity())

    override suspend fun deleteSettlement(id: String) = settlements.delete(id)

    override suspend fun recurring(id: String): SharedRecurring? = recurrings.byId(id)?.toDomain()

    override suspend fun upsertRecurring(recurring: SharedRecurring) = recurrings.upsert(recurring.toEntity())

    override suspend fun deleteRecurring(id: String) = recurrings.delete(id)

    override suspend fun insertActivity(activity: SharedActivity) = this.activity.insert(activity.toEntity())

    override suspend fun outbox(): List<SharedOutboxItem> = outbox.all().map { it.toItem() }

    override suspend fun outboxItem(id: Long): SharedOutboxItem? = outbox.byId(id)?.toItem()

    override suspend fun outboxFor(type: SharedEntityType, entityId: String): SharedOutboxItem? =
        outbox.forEntity(type.name, entityId)?.toItem()

    override suspend fun insertOutbox(item: SharedOutboxItem): Long = outbox.insert(item.copy(id = 0).toEntity())

    override suspend fun updateOutbox(item: SharedOutboxItem) = outbox.update(item.toEntity())

    override suspend fun deleteOutbox(id: Long) = outbox.delete(id)
}
