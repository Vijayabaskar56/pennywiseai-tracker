package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** A row of `shared_links`: [transactionId] belongs to the Shared expense or Settlement [entityId]. */
data class SharedLink(
    val id: Long = 0,
    val groupId: String,
    val entityType: SharedEntityType,
    val entityId: String,
    val transactionId: Long,
    val transactionHash: String,
    /** What unlinking restores; see [SharedLinkEntity.originalType]. */
    val original: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val ORIGINAL_EXCLUDED = "excluded_from_analytics=1"
        const val ORIGINAL_COUNTED = "excluded_from_analytics=0"
    }
}

/** A row of `shared_projections`: the values the reconcile last wrote on an entry. */
data class SharedProjection(
    val expenseId: String,
    val groupId: String,
    val transactionHash: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val category: String?,
    val description: String,
    val updatedAt: Long,
)

/** The Shared side the budget link reads and writes. Room in the app, maps in tests. */
interface SharedLinkStore {
    /** Group ID → my member ID, for the groups on this phone (not deleted) whose member ID is known. */
    suspend fun myMemberIds(): Map<String, String>
    suspend fun groupName(groupId: String): String?

    /** Every expense of [groupIds], deleted ones included. */
    suspend fun expensesIn(groupIds: Set<String>): List<SharedExpense>
    suspend fun expense(id: String): SharedExpense?
    suspend fun settlement(id: String): SharedSettlement?

    suspend fun links(): List<SharedLink>
    fun observeLinks(): Flow<List<SharedLink>>
    suspend fun insertLink(link: SharedLink): Long
    suspend fun updateLink(link: SharedLink)
    suspend fun deleteLink(id: Long)

    suspend fun projections(): List<SharedProjection>
    suspend fun upsertProjection(projection: SharedProjection)
    suspend fun deleteProjection(expenseId: String)
}

/** The main PennyWise database as the budget link uses it: plain row writes, no repository side effects. */
interface BudgetLedger {
    suspend fun byId(id: Long): TransactionEntity?

    /** The live row whose `transaction_hash` is exactly [hash]. */
    suspend fun liveByHash(hash: String): TransactionEntity?
    suspend fun hasSplitShares(id: Long): Boolean
    suspend fun insert(transaction: TransactionEntity): Long
    suspend fun update(transaction: TransactionEntity)
    suspend fun delete(id: Long)

    /** Live rows of [type] in [currency] since [since], newest first. */
    suspend fun recent(currency: String, type: TransactionType, since: LocalDateTime): List<TransactionEntity>
}

class RoomSharedLinkStore @Inject constructor(db: SharedExpensesDatabase) : SharedLinkStore {
    private val groups = db.groupDao()
    private val expenses = db.expenseDao()
    private val settlements = db.settlementDao()
    private val linkDao = db.linkDao()
    private val projectionDao = db.projectionDao()

    override suspend fun myMemberIds(): Map<String, String> =
        groups.all().filter { it.deletedAt == null && it.myMemberId != null }.associate { it.id to it.myMemberId!! }

    override suspend fun groupName(groupId: String): String? = groups.byId(groupId)?.name

    override suspend fun expensesIn(groupIds: Set<String>): List<SharedExpense> {
        if (groupIds.isEmpty()) return emptyList()
        val ids = groupIds.toList()
        val payers = expenses.payersInGroups(ids).groupBy { it.expenseId }
        val shares = expenses.sharesInGroups(ids).groupBy { it.expenseId }
        return expenses.inGroups(ids).map { it.toDomain(payers[it.id].orEmpty(), shares[it.id].orEmpty()) }
    }

    override suspend fun expense(id: String): SharedExpense? =
        expenses.byId(id)?.toDomain(expenses.payers(id), expenses.shares(id))

    override suspend fun settlement(id: String): SharedSettlement? = settlements.byId(id)?.toDomain()

    override suspend fun links(): List<SharedLink> = linkDao.all().mapNotNull { it.toLink() }

    override fun observeLinks(): Flow<List<SharedLink>> = linkDao.observeAll().map { rows -> rows.mapNotNull { it.toLink() } }

    override suspend fun insertLink(link: SharedLink): Long = linkDao.upsert(link.copy(id = 0).toEntity())

    override suspend fun updateLink(link: SharedLink) {
        linkDao.upsert(link.toEntity())
    }

    override suspend fun deleteLink(id: Long) = linkDao.delete(id)

    override suspend fun projections(): List<SharedProjection> = projectionDao.all().map {
        SharedProjection(
            it.expenseId, it.groupId, it.transactionHash, BigDecimal(it.amount), it.currency, LocalDate.parse(it.date),
            it.category, it.description, it.updatedAt,
        )
    }

    override suspend fun upsertProjection(projection: SharedProjection) = projectionDao.upsert(
        SharedProjectionEntity(
            expenseId = projection.expenseId, groupId = projection.groupId, transactionHash = projection.transactionHash,
            amount = projection.amount.toPlainString(), currency = projection.currency, date = projection.date.toString(),
            category = projection.category, description = projection.description, updatedAt = projection.updatedAt,
        ),
    )

    override suspend fun deleteProjection(expenseId: String) = projectionDao.delete(expenseId)

    private fun SharedLinkEntity.toLink(): SharedLink? {
        val type = runCatching { SharedEntityType.valueOf(entityType) }.getOrNull() ?: return null
        return SharedLink(id, groupId, type, entityId, transactionId, transactionHash, originalType, createdAt)
    }

    private fun SharedLink.toEntity() = SharedLinkEntity(
        id = id, groupId = groupId, entityType = entityType.name, entityId = entityId, transactionId = transactionId,
        transactionHash = transactionHash, originalType = original, createdAt = createdAt,
    )
}

class RoomBudgetLedger @Inject constructor(private val dao: TransactionDao) : BudgetLedger {
    override suspend fun byId(id: Long): TransactionEntity? = dao.getTransactionById(id)

    override suspend fun liveByHash(hash: String): TransactionEntity? = dao.getTransactionByHash(hash)?.takeIf { !it.isDeleted }

    override suspend fun hasSplitShares(id: Long): Boolean = dao.getShareAmounts(id).isNotEmpty()

    override suspend fun insert(transaction: TransactionEntity): Long = dao.insertTransaction(transaction)

    override suspend fun update(transaction: TransactionEntity) = dao.updateTransaction(transaction)

    override suspend fun delete(id: Long) = dao.deleteTransactionById(id)

    override suspend fun recent(currency: String, type: TransactionType, since: LocalDateTime): List<TransactionEntity> =
        dao.getTransactionsFiltered(since, LocalDateTime.now().plusDays(1), currency, type).first()
}
