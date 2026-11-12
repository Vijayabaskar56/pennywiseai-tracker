package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.math.BigDecimal
import java.time.LocalDateTime

/** [SharedLinkStore] in maps. [writes] counts every write, to prove a run changed nothing. */
class FakeSharedLinkStore : SharedLinkStore {
    val memberIds = linkedMapOf<String, String>()
    val groupNames = linkedMapOf<String, String>()
    val expenses = linkedMapOf<String, SharedExpense>()
    val settlements = linkedMapOf<String, SharedSettlement>()
    private val linkFlow = MutableStateFlow<List<SharedLink>>(emptyList())
    val projectionRows = linkedMapOf<String, SharedProjection>()
    private var nextLinkId = 1L
    var writes = 0

    override suspend fun myMemberIds(): Map<String, String> = memberIds.toMap()
    override suspend fun groupName(groupId: String): String? = groupNames[groupId]
    override suspend fun expensesIn(groupIds: Set<String>): List<SharedExpense> = expenses.values.filter { it.groupId in groupIds }
    override suspend fun expense(id: String): SharedExpense? = expenses[id]
    override suspend fun settlement(id: String): SharedSettlement? = settlements[id]

    override suspend fun links(): List<SharedLink> = linkFlow.value
    override fun observeLinks(): Flow<List<SharedLink>> = linkFlow

    override suspend fun insertLink(link: SharedLink): Long {
        writes++
        check(linkFlow.value.none { it.transactionId == link.transactionId }) { "transaction_id is unique" }
        val id = nextLinkId++
        linkFlow.value = linkFlow.value + link.copy(id = id)
        return id
    }

    override suspend fun updateLink(link: SharedLink) {
        writes++
        linkFlow.value = linkFlow.value.map { if (it.id == link.id) link else it }
    }

    override suspend fun deleteLink(id: Long) {
        writes++
        linkFlow.value = linkFlow.value.filter { it.id != id }
    }

    override suspend fun projections(): List<SharedProjection> = projectionRows.values.toList()

    override suspend fun upsertProjection(projection: SharedProjection) {
        writes++
        projectionRows[projection.expenseId] = projection
    }

    override suspend fun deleteProjection(expenseId: String) {
        writes++
        projectionRows.remove(expenseId)
    }
}

/** [BudgetLedger] in a map, deleting the way the app does (soft delete renames the hash). */
class FakeBudgetLedger : BudgetLedger {
    val rows = linkedMapOf<Long, TransactionEntity>()
    val splitShares = mutableSetOf<Long>()
    private var nextId = 100L
    var writes = 0

    override suspend fun byId(id: Long): TransactionEntity? = rows[id]
    override suspend fun liveByHash(hash: String): TransactionEntity? = rows.values.firstOrNull { it.transactionHash == hash && !it.isDeleted }
    override suspend fun hasSplitShares(id: Long): Boolean = id in splitShares

    override suspend fun insert(transaction: TransactionEntity): Long {
        writes++
        val id = nextId++
        rows[id] = transaction.copy(id = id)
        return id
    }

    override suspend fun update(transaction: TransactionEntity) {
        writes++
        rows[transaction.id] = transaction
    }

    override suspend fun delete(id: Long) {
        writes++
        rows.remove(id)
    }

    override suspend fun recent(currency: String, type: TransactionType, since: LocalDateTime): List<TransactionEntity> =
        rows.values.filter { !it.isDeleted && it.currency == currency && it.transactionType == type && it.dateTime >= since }
            .sortedByDescending { it.dateTime }

    /** What `TransactionDao.softDeleteTransaction` does. */
    fun softDelete(id: Long) {
        val t = rows.getValue(id)
        rows[id] = t.copy(isDeleted = true, transactionHash = "DELETED_${id}_${t.transactionHash}")
    }

    fun add(
        id: Long,
        amount: String,
        type: TransactionType = TransactionType.EXPENSE,
        currency: String = "INR",
        hash: String = "sms$id",
        excluded: Boolean = false,
        lent: String? = null,
    ): TransactionEntity = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = "Shop", category = "Food", transactionType = type,
        dateTime = LocalDateTime.now().minusDays(1), transactionHash = hash, currency = currency,
        excludedFromAnalytics = excluded, lentSharesTotal = lent?.let(::BigDecimal),
        createdAt = LocalDateTime.of(2026, 1, 1, 0, 0), updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
    ).also { rows[id] = it }
}
