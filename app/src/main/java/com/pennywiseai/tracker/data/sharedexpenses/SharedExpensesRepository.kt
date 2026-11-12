package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseDraft
import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseTemplate
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringDates
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SettlementDraft
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMoney
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import com.pennywiseai.tracker.domain.sharedexpenses.SharedValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a ViewModel needs for Shared groups (docs/shared-expenses.md). It
 * exposes domain models only.
 *
 * Expenses, Settlements and recurring expenses are saved on this phone at once
 * (marked pending) and sent by the background sync. Groups, members and
 * invites are online only. No action throws for an expected failure: each
 * returns a [SharedResult].
 */
@Singleton
class SharedExpensesRepository @Inject constructor(
    db: SharedExpensesDatabase,
    private val store: SharedLocalStore,
    private val remote: SharedRemote,
    private val identity: SharedIdentity,
    private val sync: SharedSyncManager,
    private val scheduler: SharedSyncScheduler,
    private val budget: SharedBudgetLink,
) {
    private val groupDao = db.groupDao()
    private val memberDao = db.memberDao()
    private val expenseDao = db.expenseDao()
    private val settlementDao = db.settlementDao()
    private val recurringDao = db.recurringDao()
    private val activityDao = db.activityDao()
    private val outboxDao = db.outboxDao()

    // ── State ──

    val identityState: StateFlow<SharedIdentityState> = identity.state

    val syncState: StateFlow<SharedSyncState> = sync.state

    /** Changes the server refused or that clash with someone else's edit. */
    val outboxProblems: Flow<List<SharedOutboxProblem>> = outboxDao.observeProblems().map { rows ->
        rows.mapNotNull { problem(it.toItem()) }
    }

    /** Groups this phone is in, oldest first; each carries this phone's member ID. */
    fun groups(): Flow<List<SharedGroup>> = groupDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** One group with all its seats (removed ones included); null when it is not on this phone. */
    fun group(groupId: String): Flow<SharedGroupDetail?> =
        combine(groupDao.observe(groupId), memberDao.observe(groupId)) { group, members ->
            group?.takeIf { it.deletedAt == null }?.let { SharedGroupDetail(it.toDomain(), members.map { m -> m.toDomain() }) }
        }

    /** The group's expenses, newest date first. [includeDeleted] keeps deleted ones, for restoring. */
    fun expenses(groupId: String, includeDeleted: Boolean = false): Flow<List<SharedExpense>> =
        combine(expenseDao.observe(groupId), expenseDao.observePayers(groupId), expenseDao.observeShares(groupId)) { rows, payers, shares ->
            val payersOf = payers.groupBy { it.expenseId }
            val sharesOf = shares.groupBy { it.expenseId }
            rows.filter { includeDeleted || it.deletedAt == null }
                .map { it.toDomain(payersOf[it.id].orEmpty(), sharesOf[it.id].orEmpty()) }
        }

    /** One expense (deleted or not), for the edit form; null when it is not on this phone. */
    fun expense(expenseId: String): Flow<SharedExpense?> =
        combine(expenseDao.observeOne(expenseId), expenseDao.observePayersOf(expenseId), expenseDao.observeSharesOf(expenseId)) { row, payers, shares ->
            row?.toDomain(payers, shares)
        }

    fun settlements(groupId: String, includeDeleted: Boolean = false): Flow<List<SharedSettlement>> =
        settlementDao.observe(groupId).map { rows -> rows.filter { includeDeleted || it.deletedAt == null }.map { it.toDomain() } }

    /** Recurring expenses, next due first. [includeStopped] keeps stopped ones. */
    fun recurring(groupId: String, includeStopped: Boolean = false): Flow<List<SharedRecurring>> =
        recurringDao.observe(groupId).map { rows -> rows.filter { includeStopped || it.deletedAt == null }.map { it.toDomain() } }

    /** The group's activity feed, newest first. */
    fun activity(groupId: String): Flow<List<SharedActivity>> =
        activityDao.observe(groupId).map { rows -> rows.mapNotNull { it.toDomain() } }

    /** The newest [limit] activity rows across all groups. */
    fun allActivity(limit: Int = 200): Flow<List<SharedActivity>> =
        activityDao.observeAll(limit).map { rows -> rows.mapNotNull { it.toDomain() } }

    // ── Identity ──

    /** Sets the name used for new groups and joining (1 to 40 characters). Works offline. */
    suspend fun setDisplayName(name: String): SharedResult<Unit> {
        SharedValidation.validateDisplayName(name)?.let { return invalid(it) }
        val trimmed = name.trim()
        identity.setDisplayName(trimmed)
        identity.credentials?.let { remote.setDisplayName(it, trimmed) } // best effort
        return SharedResult.Success(Unit)
    }

    /**
     * Switches server. Must be `https://`, or `http://` for `localhost` and
     * `127.0.0.1`. A different address forgets this phone's registration and
     * wipes the local copy of every group, unsent changes included. Returns the
     * address as stored.
     */
    suspend fun setServerAddress(address: String): SharedResult<String> {
        val normalized = SharedServerAddress.normalize(address) ?: return SharedResult.Failure(SharedError.InvalidServerAddress)
        if (normalized == identity.serverAddress) return SharedResult.Success(normalized)
        scheduler.cancel()
        sync.clearLocal { identity.changeServer(normalized) }
        return SharedResult.Success(normalized)
    }

    // ── Groups and members (online only) ──

    suspend fun createGroup(name: String, defaultCurrency: String): SharedResult<SharedGroup> {
        SharedValidation.validateGroupName(name)?.let { return invalid(it) }
        if (SharedMoney.strictFractionDigits(defaultCurrency) == null) return invalid(SharedExpenseError.INVALID_CURRENCY)
        val displayName = identity.displayName ?: return SharedResult.Failure(SharedError.DisplayNameRequired)
        val c = online().credentialsOr { return it }
        return remote.createGroup(c, name.trim(), defaultCurrency, displayName).toResult { created ->
            store.transaction {
                store.upsertGroup(created.group)
                created.members.forEach { store.upsertMember(it) }
            }
            scheduler.syncSoon()
            created.group
        }
    }

    suspend fun renameGroup(groupId: String, name: String): SharedResult<SharedGroup> {
        SharedValidation.validateGroupName(name)?.let { return invalid(it) }
        return updateGroup(groupId, name = name.trim())
    }

    /** Changes the simplify-debts switch and/or the default currency; null leaves a setting as it is. */
    suspend fun updateGroupSettings(groupId: String, simplifyDebts: Boolean? = null, defaultCurrency: String? = null): SharedResult<SharedGroup> {
        if (defaultCurrency != null && SharedMoney.strictFractionDigits(defaultCurrency) == null) return invalid(SharedExpenseError.INVALID_CURRENCY)
        return updateGroup(groupId, simplifyDebts = simplifyDebts, defaultCurrency = defaultCurrency)
    }

    private suspend fun updateGroup(groupId: String, name: String? = null, simplifyDebts: Boolean? = null, defaultCurrency: String? = null): SharedResult<SharedGroup> {
        val c = online().credentialsOr { return it }
        return remote.updateGroup(c, groupId, name, simplifyDebts, defaultCurrency).toResult { group ->
            store.transaction { store.upsertGroup(group) }
            scheduler.syncSoon()
            store.group(groupId) ?: group
        }
    }

    /** Deletes the group for every member, then drops it from this phone. */
    suspend fun deleteGroup(groupId: String): SharedResult<Unit> {
        val c = online().credentialsOr { return it }
        return remote.deleteGroup(c, groupId).toResult { sync.removeGroupLocally(groupId) }
    }

    /** Adds an empty seat that expenses can name straight away; an Invite link hands it to a person. */
    suspend fun addSeat(groupId: String, displayName: String): SharedResult<SharedMember> {
        SharedValidation.validateDisplayName(displayName)?.let { return invalid(it) }
        val c = online().credentialsOr { return it }
        return remote.addMember(c, groupId, displayName.trim()).toResult { member ->
            store.transaction { store.upsertMember(member) }
            scheduler.syncSoon()
            member
        }
    }

    suspend fun renameMember(groupId: String, memberId: String, displayName: String): SharedResult<SharedMember> {
        SharedValidation.validateDisplayName(displayName)?.let { return invalid(it) }
        val c = online().credentialsOr { return it }
        return remote.renameMember(c, groupId, memberId, displayName.trim()).toResult { member ->
            store.transaction { store.upsertMember(member) }
            scheduler.syncSoon()
            member
        }
    }

    /**
     * Removes a seat, or leaves the group when it is this phone's own seat.
     * Fails with `balance_not_zero` unless every balance of the member is zero.
     */
    suspend fun removeMember(groupId: String, memberId: String): SharedResult<Unit> {
        val c = online().credentialsOr { return it }
        val leaving = store.group(groupId)?.myMemberId == memberId
        return remote.removeMember(c, groupId, memberId).toResult {
            if (leaving) {
                sync.removeGroupLocally(groupId)
            } else {
                store.transaction {
                    store.members(groupId).firstOrNull { it.id == memberId }?.let { store.upsertMember(it.copy(removedAt = System.currentTimeMillis())) }
                }
                scheduler.syncSoon()
            }
        }
    }

    /** Leaves the group (only at a zero balance) and drops it from this phone. */
    suspend fun leaveGroup(groupId: String): SharedResult<Unit> {
        val me = store.group(groupId)?.myMemberId ?: return SharedResult.Failure(SharedError.NotFound)
        return removeMember(groupId, me)
    }

    // ── Invites (online only) ──

    /** A seat invite when [memberId] is set, otherwise an open invite. */
    suspend fun createInvite(groupId: String, memberId: String? = null): SharedResult<SharedInvite> {
        val c = online().credentialsOr { return it }
        return remote.createInvite(c, groupId, memberId).toResult { it }
    }

    suspend fun listInvites(groupId: String): SharedResult<List<SharedInviteInfo>> {
        val c = online().credentialsOr { return it }
        return remote.listInvites(c, groupId).toResult { it }
    }

    suspend fun revokeInvite(groupId: String, inviteId: String): SharedResult<Unit> {
        val c = online().credentialsOr { return it }
        return remote.revokeInvite(c, groupId, inviteId).toResult { }
    }

    /**
     * What the Invite link at [serverAddress] with [token] leads to. Refuses
     * with [SharedError.OtherServer] when [serverAddress] is not the server in
     * use; switching is the user's call ([setServerAddress]).
     */
    suspend fun previewInvite(serverAddress: String, token: String): SharedResult<SharedInvitePreview> {
        sameServer(serverAddress)?.let { return SharedResult.Failure(it) }
        val c = online().credentialsOr { return it }
        return remote.previewInvite(c, token).toResult { it }
    }

    /**
     * Joins through the Invite link at [serverAddress] with [token], as
     * [displayName] (default: the name already set, which this also sets when
     * there is none). Refuses with [SharedError.OtherServer] like [previewInvite].
     * The group is downloaded before this returns when the network allows.
     */
    suspend fun acceptInvite(serverAddress: String, token: String, displayName: String? = null): SharedResult<SharedJoin> {
        sameServer(serverAddress)?.let { return SharedResult.Failure(it) }
        val name = (displayName ?: identity.displayName)?.trim() ?: return SharedResult.Failure(SharedError.DisplayNameRequired)
        SharedValidation.validateDisplayName(name)?.let { return invalid(it) }
        if (identity.displayName == null) identity.setDisplayName(name)
        val c = online().credentialsOr { return it }
        return when (val r = remote.acceptInvite(c, token, name)) {
            is RemoteResult.Ok -> {
                sync.sync()
                SharedResult.Success(r.value)
            }
            else -> r.toResult { it }
        }
    }

    // ── Expenses, Settlements, recurring (saved here, sent by sync) ──

    /**
     * Creates ([expenseId] null) or edits an expense dated [date]. Checked with
     * [SharedValidation] first; on success the expense is on this phone at once
     * (pending) and queued.
     */
    suspend fun saveExpense(
        groupId: String,
        expenseId: String?,
        draft: ExpenseDraft,
        date: LocalDate,
        recurringId: String? = null,
    ): SharedResult<SharedExpense> {
        val me = myMemberId(groupId) ?: return SharedResult.Failure(SharedError.NotFound)
        val existing = expenseId?.let { store.expense(it) }
        if (expenseId != null && (existing == null || existing.groupId != groupId || existing.deletedAt != null)) {
            return SharedResult.Failure(SharedError.NotFound)
        }
        val named = existing?.let { e -> (e.payers.map { it.memberId } + e.shares.map { it.memberId }).toSet() }.orEmpty()
        SharedValidation.validateExpense(draft, allowedMembers(groupId, named))?.let { return invalid(it) }
        val now = System.currentTimeMillis()
        val expense = SharedExpense(
            id = expenseId ?: newId(), groupId = groupId, description = draft.description.trim(),
            amount = SharedMoney.normalize(draft.amount, draft.currency), currency = draft.currency, date = date,
            category = draft.category?.trim().orNullIfBlank(), notes = draft.notes.orNullIfBlank(), splitMethod = draft.splitMethod,
            payers = draft.payers.map { it.copy(amount = SharedMoney.normalize(it.amount, draft.currency)) },
            shares = draft.shares.map { it.copy(amount = SharedMoney.normalize(it.amount, draft.currency)) },
            recurringId = recurringId ?: existing?.recurringId, createdBy = existing?.createdBy ?: me, updatedBy = me,
            createdAt = existing?.createdAt ?: now, updatedAt = now, deletedAt = null,
            version = existing?.version ?: 0, rev = existing?.rev ?: 0, pending = true,
        )
        sync.queueSave(SharedEntity.Expense(expense))
        budget.reconcile()
        scheduler.syncSoon()
        return SharedResult.Success(expense)
    }

    suspend fun deleteExpense(groupId: String, expenseId: String): SharedResult<Unit> =
        queueDelete(groupId, SharedEntityType.EXPENSE, expenseId, store.expense(expenseId)?.groupId)

    /** Brings back a deleted expense (from the activity feed). */
    suspend fun restoreExpense(groupId: String, expenseId: String): SharedResult<Unit> {
        if (store.expense(expenseId)?.groupId != groupId) return SharedResult.Failure(SharedError.NotFound)
        if (!sync.queueRestore(expenseId)) return SharedResult.Failure(SharedError.NotFound)
        budget.reconcile()
        scheduler.syncSoon()
        return SharedResult.Success(Unit)
    }

    /** Records ([settlementId] null) or edits a Settlement dated [date]: [SettlementDraft.fromMemberId] paid [SettlementDraft.toMemberId]. */
    suspend fun saveSettlement(groupId: String, settlementId: String?, draft: SettlementDraft, date: LocalDate): SharedResult<SharedSettlement> {
        val me = myMemberId(groupId) ?: return SharedResult.Failure(SharedError.NotFound)
        val existing = settlementId?.let { store.settlement(it) }
        if (settlementId != null && (existing == null || existing.groupId != groupId || existing.deletedAt != null)) {
            return SharedResult.Failure(SharedError.NotFound)
        }
        val named = existing?.let { setOf(it.fromMemberId, it.toMemberId) }.orEmpty()
        SharedValidation.validateSettlement(draft, allowedMembers(groupId, named))?.let { return invalid(it) }
        val now = System.currentTimeMillis()
        val settlement = SharedSettlement(
            id = settlementId ?: newId(), groupId = groupId, fromMemberId = draft.fromMemberId, toMemberId = draft.toMemberId,
            amount = SharedMoney.normalize(draft.amount, draft.currency), currency = draft.currency, date = date,
            notes = draft.notes.orNullIfBlank(), createdBy = existing?.createdBy ?: me, updatedBy = me,
            createdAt = existing?.createdAt ?: now, updatedAt = now, deletedAt = null,
            version = existing?.version ?: 0, rev = existing?.rev ?: 0, pending = true,
        )
        sync.queueSave(SharedEntity.Settlement(settlement))
        budget.reconcile()
        scheduler.syncSoon()
        return SharedResult.Success(settlement)
    }

    suspend fun deleteSettlement(groupId: String, settlementId: String): SharedResult<Unit> =
        queueDelete(groupId, SharedEntityType.SETTLEMENT, settlementId, store.settlement(settlementId)?.groupId)

    /**
     * Creates ([recurringId] null) or edits a recurring expense.
     *
     * - Create: [date] is the first expense's date. That expense is recorded
     *   now (with this recurring ID) and the template next comes due one
     *   [interval] later, on the same day of the month.
     * - Edit: [date] is the next due date. Only future expenses change.
     */
    suspend fun saveRecurring(
        groupId: String,
        recurringId: String?,
        draft: ExpenseDraft,
        interval: RecurringInterval,
        date: LocalDate,
    ): SharedResult<SharedRecurring> {
        val me = myMemberId(groupId) ?: return SharedResult.Failure(SharedError.NotFound)
        val existing = recurringId?.let { store.recurring(it) }
        if (recurringId != null && (existing == null || existing.groupId != groupId || existing.deletedAt != null)) {
            return SharedResult.Failure(SharedError.NotFound)
        }
        val named = existing?.template?.let { t -> (t.payers.map { it.memberId } + t.shares.map { it.memberId }).toSet() }.orEmpty()
        SharedValidation.validateExpense(draft, allowedMembers(groupId, named))?.let { return invalid(it) }
        val now = System.currentTimeMillis()
        val template = ExpenseTemplate(
            description = draft.description.trim(), amount = SharedMoney.normalize(draft.amount, draft.currency),
            currency = draft.currency, category = draft.category?.trim().orNullIfBlank(), notes = draft.notes.orNullIfBlank(),
            splitMethod = draft.splitMethod,
            payers = draft.payers.map { it.copy(amount = SharedMoney.normalize(it.amount, draft.currency)) },
            shares = draft.shares.map { it.copy(amount = SharedMoney.normalize(it.amount, draft.currency)) },
        )
        val anchorDay = when {
            existing == null -> date.dayOfMonth
            existing.nextDate == date && existing.interval == interval -> existing.anchorDay
            else -> date.dayOfMonth
        }
        val recurring = SharedRecurring(
            id = recurringId ?: newId(), groupId = groupId, template = template, interval = interval, anchorDay = anchorDay,
            nextDate = if (existing == null) RecurringDates.next(date, interval, anchorDay) else date,
            createdBy = existing?.createdBy ?: me, updatedBy = me, createdAt = existing?.createdAt ?: now, updatedAt = now,
            deletedAt = null, version = existing?.version ?: 0, rev = existing?.rev ?: 0, pending = true,
        )
        sync.queueSave(SharedEntity.Recurring(recurring))
        if (existing == null) {
            when (val first = saveExpense(groupId, null, draft, date, recurringId = recurring.id)) {
                is SharedResult.Failure -> return first
                is SharedResult.Success -> Unit
            }
        }
        scheduler.syncSoon()
        return SharedResult.Success(recurring)
    }

    /** Stops a recurring expense; expenses it already created stay. */
    suspend fun stopRecurring(groupId: String, recurringId: String): SharedResult<Unit> =
        queueDelete(groupId, SharedEntityType.RECURRING, recurringId, store.recurring(recurringId)?.groupId)

    // ── Outbox problems ──

    /**
     * Settles a conflict ([SharedOutboxProblem.Conflict.itemId]). [keepMine]
     * sends the user's version again on top of the server's; otherwise the
     * server's version replaces the local one.
     */
    suspend fun resolveConflict(itemId: Long, keepMine: Boolean): SharedResult<Unit> =
        sync.resolveConflict(itemId, keepMine).also {
            budget.reconcile()
            if (it is SharedResult.Success && keepMine) scheduler.syncSoon()
        }

    /** Drops a failed (or conflicting) change and puts the server's copy back. */
    suspend fun discardChange(itemId: Long): SharedResult<Unit> = sync.discard(itemId).also { budget.reconcile() }

    // ── Link to the budget (SharedBudgetLink) ──

    /** Links my payment [transactionId] to the expense [expenseId] (I must have paid on it). */
    suspend fun linkExpense(transactionId: Long, expenseId: String): SharedResult<Unit> = budget.linkExpense(transactionId, expenseId)

    /** Links [transactionId] to the Settlement [settlementId] (my debit when I paid, my credit when I received). */
    suspend fun linkSettlement(transactionId: Long, settlementId: String): SharedResult<Unit> = budget.linkSettlement(transactionId, settlementId)

    /** Unlinks [transactionId]; it counts exactly as before it was linked. */
    suspend fun unlinkTransaction(transactionId: Long): SharedResult<Unit> = budget.unlink(transactionId)

    /** How [transaction] is tied to a Shared group, or null. */
    suspend fun tieOf(transaction: com.pennywiseai.tracker.data.database.entity.TransactionEntity): SharedTransactionTie? = budget.tieOf(transaction)

    /** [tieOf], kept up to date as links change. */
    fun observeTie(transaction: com.pennywiseai.tracker.data.database.entity.TransactionEntity): Flow<SharedTransactionTie?> = budget.observeTie(transaction)

    /** The bank transaction linked to an expense or Settlement. */
    fun linkedTransaction(type: SharedEntityType, entityId: String): Flow<SharedLinkedTransaction?> = budget.linkedTo(type, entityId)

    /** Transactions that could be linked; see [SharedBudgetLink.candidates]. */
    suspend fun linkCandidates(currency: String, credit: Boolean, amount: java.math.BigDecimal?): List<SharedLinkedTransaction> =
        budget.candidates(currency, credit, amount)

    /** [transactionId] as a link candidate, and whether it is a credit; null when it is gone. */
    suspend fun linkableTransaction(transactionId: Long): SharedLinkable? = budget.linkable(transactionId)

    /** Runs the budget reconcile (app start, after a restore). */
    suspend fun reconcileBudget() = budget.reconcile()

    /** Sends and fetches now (pull-to-refresh, opening the tab or a group). */
    suspend fun syncNow(): SharedSyncResult {
        if (identity.credentials != null) scheduler.ensurePeriodic()
        return sync.sync()
    }

    // ── Helpers ──

    private suspend fun queueDelete(groupId: String, type: SharedEntityType, id: String, storedGroupId: String?): SharedResult<Unit> {
        if (storedGroupId != groupId || !sync.queueDelete(type, id)) return SharedResult.Failure(SharedError.NotFound)
        budget.reconcile()
        scheduler.syncSoon()
        return SharedResult.Success(Unit)
    }

    private suspend fun myMemberId(groupId: String): String? =
        store.group(groupId)?.takeIf { it.deletedAt == null }?.myMemberId

    /** Active seats, plus removed ones the stored row already names (the server accepts those). */
    private suspend fun allowedMembers(groupId: String, named: Set<String>): Set<String> =
        store.members(groupId).filter { it.removedAt == null || it.id in named }.map { it.id }.toSet()

    private fun newId() = UUID.randomUUID().toString()

    private fun invalid(reason: SharedExpenseError) = SharedResult.Failure(SharedError.Invalid(reason))

    private fun sameServer(serverAddress: String): SharedError? {
        val invite = SharedServerAddress.normalize(serverAddress) ?: return SharedError.InvalidServerAddress
        val current = identity.serverAddress
        return if (invite == current) null else SharedError.OtherServer(invite, current)
    }

    /** Registers if needed; once registered, the periodic sync runs. */
    private suspend fun online(): SharedResult<SharedCredentials> =
        identity.ensureRegistered().also { if (it is SharedResult.Success) scheduler.ensurePeriodic() }

    private inline fun SharedResult<SharedCredentials>.credentialsOr(fail: (SharedResult.Failure) -> Nothing): SharedCredentials =
        when (this) {
            is SharedResult.Success -> value
            is SharedResult.Failure -> fail(this)
        }

    private inline fun <T, R> RemoteResult<T>.toResult(onOk: (T) -> R): SharedResult<R> = when (this) {
        is RemoteResult.Ok -> SharedResult.Success(onOk(value))
        is RemoteResult.Err -> SharedResult.Failure(error)
        is RemoteResult.Conflict -> SharedResult.Failure(SharedError.Server(409, SharedServerCode.CONFLICT, "Someone changed this first"))
    }

    private suspend fun problem(item: SharedOutboxItem): SharedOutboxProblem? {
        val mine = store.entity(item.entityType, item.entityId)
        return when (item.status) {
            SharedOutboxStatus.PENDING -> null
            SharedOutboxStatus.FAILED -> SharedOutboxProblem.Failed(
                item.id, item.groupId, item.entityType, item.entityId, item.operation,
                item.error ?: SharedError.Server(0, SharedServerCode.UNKNOWN, ""), mine,
            )
            SharedOutboxStatus.CONFLICT -> SharedOutboxProblem.Conflict(
                item.id, item.groupId, item.entityType, item.entityId, item.operation, mine,
                SharedEntityCodec.decode(item.entityType, item.serverJson) ?: return null,
            )
        }
    }
}
