package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.repository.TransactionHold
import com.pennywiseai.tracker.data.repository.TransactionHolds
import com.pennywiseai.tracker.domain.sharedexpenses.SharedBudgetProjection
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Why a transaction could not be linked to a Shared expense or Settlement. */
enum class SharedLinkRefusal {
    TRANSACTION_GONE,

    /** An expense or a payment I made needs a debit. */
    NOT_A_DEBIT,

    /** A payment I received needs a credit. */
    NOT_A_CREDIT,
    CURRENCY_MISMATCH,

    /** The transaction is already linked to a Shared expense or Settlement. */
    TRANSACTION_TAKEN,

    /** The transaction is a local Split bill or marked as a loan. */
    SPLIT_OR_LOAN,

    /** The transaction is a budget entry the app made for a Shared expense. */
    APP_ENTRY,

    /** I paid nothing on the expense. */
    NOT_A_PAYER,

    /** I neither paid nor received the Settlement. */
    NOT_A_PARTY,

    /** The expense or Settlement already has a linked transaction. */
    ALREADY_LINKED,
}

/** How a transaction is tied to a Shared group, for the transaction's detail screen. */
sealed interface SharedTransactionTie {
    val groupId: String
    val groupName: String?

    /** A bank transaction linked to a Shared expense; [myShare] is my Share of it (null when the expense is gone). */
    data class Expense(
        override val groupId: String,
        override val groupName: String?,
        val expenseId: String,
        val description: String?,
        val myShare: BigDecimal?,
        val currency: String,
    ) : SharedTransactionTie

    /** A bank transaction linked to a Settlement. */
    data class Settlement(override val groupId: String, override val groupName: String?, val settlementId: String) : SharedTransactionTie

    /** A budget entry the app made for a Shared expense; its amount follows the expense. */
    data class Entry(override val groupId: String, override val groupName: String?, val expenseId: String) : SharedTransactionTie
}

/** A bank transaction as the Shared screens show it. */
data class SharedLinkedTransaction(
    val transactionId: Long,
    val merchant: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    /** Same amount as what it would be linked to (a candidate) . */
    val exactMatch: Boolean = false,
)

/** A transaction a Shared screen was opened from. */
data class SharedLinkable(val linked: SharedLinkedTransaction, val credit: Boolean)

/**
 * The link between Shared groups and the user's own budget
 * (docs/shared-expenses.md, "Link to your budget"). My budget counts my Share
 * of a Shared expense on the expense's date, whoever paid, and never counts a
 * Settlement.
 *
 * [reconcile] makes the main database match the Shared expenses, the links and
 * the projections. It can run any number of times: a second run writes nothing.
 * It runs after every sync, after every local change and once at app start.
 *
 * - A transaction linked to an expense gets `lent_shares_total = max(P − S, 0)`.
 * - The entry (`transaction_hash = shared:<expense id>`) is `max(S − P, 0)`
 *   when linked, `S` when not, and absent when zero.
 * - A transaction linked to a Settlement is `excluded_from_analytics`, its
 *   value before linking kept on the link and put back on unlink.
 * - Links and entries of a group no longer on this phone are left as they are.
 */
@Singleton
class SharedBudgetLink internal constructor(
    private val store: SharedLinkStore,
    private val ledger: BudgetLedger,
    private val clock: () -> Long,
) : TransactionHolds {
    @Inject constructor(store: SharedLinkStore, ledger: BudgetLedger) : this(store, ledger, System::currentTimeMillis)

    private val lock = Mutex()

    // ── Reconcile ──

    suspend fun reconcile() = lock.withLock { reconcileLocked() }

    private suspend fun reconcileLocked() {
        val mine = store.myMemberIds()
        val groups = mine.keys
        val expenses = store.expensesIn(groups).associateBy { it.id }
        val linkedExpenses = HashSet<String>()

        for (link in store.links()) {
            if (link.groupId !in groups) continue // a group gone from this phone: leave it as it is
            val tx = resolve(link) ?: continue
            when (link.entityType) {
                SharedEntityType.EXPENSE -> {
                    val expense = expenses[link.entityId]
                    if (expense == null || !fits(tx, TransactionType.EXPENSE, expense.currency)) {
                        setLent(tx, null)
                        store.deleteLink(link.id)
                        continue
                    }
                    // A deleted expense keeps its link, so restoring it brings the value back.
                    val lent = SharedBudgetProjection.project(expense, mine.getValue(link.groupId), linked = true).lentSharesTotal
                    setLent(tx, lent)
                    if (expense.deletedAt == null) linkedExpenses += expense.id
                }
                SharedEntityType.SETTLEMENT -> {
                    val settlement = store.settlement(link.entityId)
                    if (settlement == null || settlement.deletedAt != null) {
                        setExcluded(tx, link.original == SharedLink.ORIGINAL_EXCLUDED)
                        store.deleteLink(link.id)
                    } else {
                        setExcluded(tx, true)
                    }
                }
                SharedEntityType.RECURRING -> store.deleteLink(link.id)
            }
        }

        val projections = store.projections().associateBy { it.expenseId }
        for (expense in expenses.values) {
            val me = mine[expense.groupId] ?: continue
            val amount = SharedBudgetProjection.project(expense, me, linked = expense.id in linkedExpenses).entryAmount
            reconcileEntry(expense, amount, projections[expense.id])
        }
        // The expense row itself is gone from a group still on this phone.
        for (p in projections.values) {
            if (p.groupId in groups && p.expenseId !in expenses) {
                ledger.liveByHash(p.transactionHash)?.let { ledger.delete(it.id) }
                store.deleteProjection(p.expenseId)
            }
        }
    }

    /**
     * The live transaction [link] points at, re-pointing the link when a
     * re-parse gave the same transaction a new ID. A transaction the user
     * deleted drops the link (a Settlement's flag is put back first, so the
     * deleted row is as it was if restored).
     */
    private suspend fun resolve(link: SharedLink): TransactionEntity? {
        val byId = ledger.byId(link.transactionId)
        val tx = when {
            byId != null && !byId.isDeleted -> byId
            byId != null -> null
            else -> ledger.liveByHash(link.transactionHash)
        }
        if (tx == null) {
            if (byId != null) {
                when (link.entityType) {
                    SharedEntityType.SETTLEMENT -> setExcluded(byId, link.original == SharedLink.ORIGINAL_EXCLUDED)
                    else -> setLent(byId, null)
                }
            }
            store.deleteLink(link.id)
            return null
        }
        if (tx.id != link.transactionId || tx.transactionHash != link.transactionHash) {
            store.updateLink(link.copy(transactionId = tx.id, transactionHash = tx.transactionHash))
        }
        return tx
    }

    private suspend fun reconcileEntry(expense: SharedExpense, amount: BigDecimal?, projection: SharedProjection?) {
        val hash = entryHash(expense.id)
        val live = ledger.liveByHash(hash)
        if (amount == null) {
            live?.let { ledger.delete(it.id) }
            if (projection != null) store.deleteProjection(expense.id)
            return
        }
        val wanted = projectionOf(expense, amount)
        if (live == null) {
            if (projection != null) return // the user deleted the entry: it stays deleted
            ledger.insert(newEntry(expense, amount))
            store.upsertProjection(wanted)
            return
        }
        // Owned always: amount, currency, type and date (the user's time of day stays).
        var next = live.copy(
            amount = if (live.amount.compareTo(amount) == 0) live.amount else amount,
            currency = expense.currency,
            transactionType = TransactionType.EXPENSE,
            dateTime = if (live.dateTime.toLocalDate() == expense.date) live.dateTime else expense.date.atTime(ENTRY_TIME),
            lentSharesTotal = null,
        )
        // Owned only when the expense's own value changed since the last run, so a hand edit survives.
        if (projection != null && projection.description != expense.description) next = next.copy(merchantName = expense.description)
        if (projection != null && projection.category != expense.category) next = next.copy(category = entryCategory(expense))
        if (next != live) ledger.update(next.copy(updatedAt = LocalDateTime.now()))
        if (projection == null || !projection.sameAs(wanted)) store.upsertProjection(wanted)
    }

    private suspend fun setLent(tx: TransactionEntity, value: BigDecimal?) {
        val current = tx.lentSharesTotal
        val same = if (value == null || current == null) value == current else value.compareTo(current) == 0
        if (same) return
        // A split bill's total is its own (never linked, but never wiped either).
        if (value == null && ledger.hasSplitShares(tx.id)) return
        ledger.update(tx.copy(lentSharesTotal = value, updatedAt = LocalDateTime.now()))
    }

    private suspend fun setExcluded(tx: TransactionEntity, excluded: Boolean) {
        if (tx.excludedFromAnalytics == excluded) return
        ledger.update(tx.copy(excludedFromAnalytics = excluded, updatedAt = LocalDateTime.now()))
    }

    private fun fits(tx: TransactionEntity, type: TransactionType, currency: String) =
        tx.transactionType == type && tx.currency.equals(currency, ignoreCase = true)

    private fun newEntry(expense: SharedExpense, amount: BigDecimal): TransactionEntity {
        val now = LocalDateTime.now()
        // As a manual transaction with no account: no SMS, "Manual Entry", no profile.
        return TransactionEntity(
            amount = amount,
            merchantName = expense.description,
            category = entryCategory(expense),
            transactionType = TransactionType.EXPENSE,
            dateTime = expense.date.atTime(ENTRY_TIME),
            bankName = MANUAL_BANK,
            transactionHash = entryHash(expense.id),
            currency = expense.currency,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun projectionOf(expense: SharedExpense, amount: BigDecimal) = SharedProjection(
        expenseId = expense.id, groupId = expense.groupId, transactionHash = entryHash(expense.id), amount = amount,
        currency = expense.currency, date = expense.date, category = expense.category, description = expense.description,
        updatedAt = clock(),
    )

    private fun SharedProjection.sameAs(other: SharedProjection) =
        groupId == other.groupId && transactionHash == other.transactionHash && amount.compareTo(other.amount) == 0 &&
            currency == other.currency && date == other.date && category == other.category && description == other.description

    // ── Linking ──

    /** Links [transactionId] (my payment) to the Shared expense [expenseId]; I must be one of its payers. */
    suspend fun linkExpense(transactionId: Long, expenseId: String): SharedResult<Unit> = lock.withLock {
        val expense = store.expense(expenseId)?.takeIf { it.deletedAt == null } ?: return@withLock notFound()
        val me = store.myMemberIds()[expense.groupId] ?: return@withLock notFound()
        if (SharedBudgetProjection.paidBy(expense, me).signum() <= 0) return@withLock refuse(SharedLinkRefusal.NOT_A_PAYER)
        if (store.links().any { it.entityType == SharedEntityType.EXPENSE && it.entityId == expenseId }) {
            return@withLock refuse(SharedLinkRefusal.ALREADY_LINKED)
        }
        val tx = ledger.byId(transactionId)
        checkLinkable(tx, TransactionType.EXPENSE, expense.currency)?.let { return@withLock refuse(it) }
        store.insertLink(
            SharedLink(
                groupId = expense.groupId, entityType = SharedEntityType.EXPENSE, entityId = expenseId,
                transactionId = transactionId, transactionHash = tx!!.transactionHash, createdAt = clock(),
            ),
        )
        reconcileLocked()
        SharedResult.Success(Unit)
    }

    /** Links [transactionId] to the Settlement [settlementId]: my debit when I paid it, my credit when I received it. */
    suspend fun linkSettlement(transactionId: Long, settlementId: String): SharedResult<Unit> = lock.withLock {
        val settlement = store.settlement(settlementId)?.takeIf { it.deletedAt == null } ?: return@withLock notFound()
        val me = store.myMemberIds()[settlement.groupId] ?: return@withLock notFound()
        val type = when (me) {
            settlement.fromMemberId -> TransactionType.EXPENSE
            settlement.toMemberId -> TransactionType.INCOME
            else -> return@withLock refuse(SharedLinkRefusal.NOT_A_PARTY)
        }
        if (store.links().any { it.entityType == SharedEntityType.SETTLEMENT && it.entityId == settlementId }) {
            return@withLock refuse(SharedLinkRefusal.ALREADY_LINKED)
        }
        val tx = ledger.byId(transactionId)
        checkLinkable(tx, type, settlement.currency)?.let { return@withLock refuse(it) }
        store.insertLink(
            SharedLink(
                groupId = settlement.groupId, entityType = SharedEntityType.SETTLEMENT, entityId = settlementId,
                transactionId = transactionId, transactionHash = tx!!.transactionHash,
                original = if (tx.excludedFromAnalytics) SharedLink.ORIGINAL_EXCLUDED else SharedLink.ORIGINAL_COUNTED,
                createdAt = clock(),
            ),
        )
        reconcileLocked()
        SharedResult.Success(Unit)
    }

    /** Unlinks [transactionId], giving it back what it was before it was linked. */
    suspend fun unlink(transactionId: Long): SharedResult<Unit> = lock.withLock {
        val link = store.links().firstOrNull { it.transactionId == transactionId } ?: return@withLock notFound()
        ledger.byId(transactionId)?.let { tx ->
            when (link.entityType) {
                SharedEntityType.SETTLEMENT -> setExcluded(tx, link.original == SharedLink.ORIGINAL_EXCLUDED)
                else -> setLent(tx, null)
            }
        }
        store.deleteLink(link.id)
        reconcileLocked()
        SharedResult.Success(Unit)
    }

    private suspend fun checkLinkable(tx: TransactionEntity?, type: TransactionType, currency: String): SharedLinkRefusal? = when {
        tx == null || tx.isDeleted -> SharedLinkRefusal.TRANSACTION_GONE
        isEntryHash(tx.transactionHash) -> SharedLinkRefusal.APP_ENTRY
        tx.transactionType != type -> if (type == TransactionType.INCOME) SharedLinkRefusal.NOT_A_CREDIT else SharedLinkRefusal.NOT_A_DEBIT
        !tx.currency.equals(currency, ignoreCase = true) -> SharedLinkRefusal.CURRENCY_MISMATCH
        store.links().any { it.transactionId == tx.id } -> SharedLinkRefusal.TRANSACTION_TAKEN
        tx.loanId != null || tx.lentSharesTotal != null || ledger.hasSplitShares(tx.id) -> SharedLinkRefusal.SPLIT_OR_LOAN
        else -> null
    }

    // ── Reading ──

    override suspend fun holdOf(transaction: TransactionEntity): TransactionHold? {
        if (isEntryHash(transaction.transactionHash)) return TransactionHold.SHARED_ENTRY
        return when (store.links().firstOrNull { it.transactionId == transaction.id }?.entityType) {
            SharedEntityType.EXPENSE -> TransactionHold.SHARED_EXPENSE
            SharedEntityType.SETTLEMENT -> TransactionHold.SHARED_SETTLEMENT
            else -> null
        }
    }

    /** How [transaction] is tied to a Shared group, or null. */
    suspend fun tieOf(transaction: TransactionEntity): SharedTransactionTie? {
        if (isEntryHash(transaction.transactionHash)) {
            val expenseId = transaction.transactionHash.removePrefix(ENTRY_PREFIX)
            val groupId = store.expense(expenseId)?.groupId ?: store.projections().firstOrNull { it.expenseId == expenseId }?.groupId
                ?: return null
            return SharedTransactionTie.Entry(groupId, store.groupName(groupId), expenseId)
        }
        val link = store.links().firstOrNull { it.transactionId == transaction.id } ?: return null
        val name = store.groupName(link.groupId)
        return when (link.entityType) {
            SharedEntityType.EXPENSE -> {
                val expense = store.expense(link.entityId)?.takeIf { it.deletedAt == null }
                val me = store.myMemberIds()[link.groupId]
                SharedTransactionTie.Expense(
                    link.groupId, name, link.entityId, expense?.description,
                    myShare = if (expense != null && me != null) SharedBudgetProjection.shareOf(expense, me) else null,
                    currency = expense?.currency ?: transaction.currency,
                )
            }
            SharedEntityType.SETTLEMENT -> SharedTransactionTie.Settlement(link.groupId, name, link.entityId)
            SharedEntityType.RECURRING -> null
        }
    }

    /** [tieOf], again whenever a link changes. */
    fun observeTie(transaction: TransactionEntity): Flow<SharedTransactionTie?> = store.observeLinks().map { tieOf(transaction) }

    /** The transaction linked to the expense or Settlement [entityId], or null. */
    fun linkedTo(type: SharedEntityType, entityId: String): Flow<SharedLinkedTransaction?> = store.observeLinks().map { links ->
        val link = links.firstOrNull { it.entityType == type && it.entityId == entityId } ?: return@map null
        ledger.byId(link.transactionId)?.takeIf { !it.isDeleted }?.toLinked(false)
    }

    /**
     * Recent transactions that could be linked: [credit] ones (else debits) in
     * [currency] from the last [DAYS] days, not linked, split, a loan or an
     * entry. Those of exactly [amount] come first, then newest first.
     */
    suspend fun candidates(currency: String, credit: Boolean, amount: BigDecimal?): List<SharedLinkedTransaction> {
        val type = if (credit) TransactionType.INCOME else TransactionType.EXPENSE
        val taken = store.links().map { it.transactionId }.toSet()
        return ledger.recent(currency, type, LocalDateTime.now().minusDays(DAYS))
            .filter { it.id !in taken && !isEntryHash(it.transactionHash) && it.loanId == null && it.lentSharesTotal == null }
            .map { it.toLinked(amount != null && it.amount.compareTo(amount) == 0) }
            .sortedWith(compareByDescending<SharedLinkedTransaction> { it.exactMatch }.thenByDescending { it.date })
            .take(MAX_CANDIDATES)
    }

    /** [transactionId] as the Shared screens show it, and whether it is a credit; null when it is gone. */
    suspend fun linkable(transactionId: Long): SharedLinkable? =
        ledger.byId(transactionId)?.takeIf { !it.isDeleted }?.let { SharedLinkable(it.toLinked(false), it.transactionType == TransactionType.INCOME) }

    private fun TransactionEntity.toLinked(exact: Boolean) =
        SharedLinkedTransaction(id, merchantName, amount, currency, dateTime.toLocalDate(), exact)

    private fun notFound() = SharedResult.Failure(SharedError.NotFound)

    private fun refuse(reason: SharedLinkRefusal) = SharedResult.Failure(SharedError.LinkRefused(reason))

    companion object {
        const val ENTRY_PREFIX = "shared:"
        const val DEFAULT_CATEGORY = "Others"
        private const val MANUAL_BANK = "Manual Entry"
        private val ENTRY_TIME: LocalTime = LocalTime.NOON
        private const val DAYS = 90L
        private const val MAX_CANDIDATES = 50

        fun entryHash(expenseId: String) = ENTRY_PREFIX + expenseId

        fun isEntryHash(hash: String) = hash.startsWith(ENTRY_PREFIX)

        private fun entryCategory(expense: SharedExpense) = expense.category?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORY
    }
}
