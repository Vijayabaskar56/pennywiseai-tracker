package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** This phone's registration as the sync manager needs it. */
interface SharedSession {
    /** Credentials for the current server; null until this phone registered there (the feature is not in use). */
    val credentials: SharedCredentials?

    /** When a sync last finished without an error. */
    var lastSyncAt: Long?
}

/**
 * Keeps the local copy and the server in step (docs/shared-expenses.md, "Sync").
 *
 * Local changes go into the outbox through [queueSave], [queueDelete] and
 * [queueRestore]: the row is written at once (marked pending) and the outbox
 * holds at most one item per row, so later edits coalesce into it. [sync]
 * pushes the outbox in order, then pulls every group whose revision moved.
 * Only one sync runs at a time.
 */
@Singleton
class SharedSyncManager internal constructor(
    private val store: SharedLocalStore,
    private val remote: SharedRemote,
    private val session: SharedSession,
    /** Runs after every sync, finished or not (the budget reconcile). */
    private val onSynced: suspend () -> Unit = {},
    private val clock: () -> Long,
) {
    @Inject constructor(store: SharedLocalStore, remote: SharedRemote, session: SharedSession, budget: SharedBudgetLink) :
        this(store, remote, session, { budget.reconcile() }, System::currentTimeMillis)

    private val syncLock = Mutex()
    private val _state = MutableStateFlow(SharedSyncState(lastSuccessAt = session.lastSyncAt))
    val state: StateFlow<SharedSyncState> = _state.asStateFlow()

    // ── Sync ──

    suspend fun sync(): SharedSyncResult = syncLocked().also { if (it.status != SharedSyncStatus.NOT_IN_USE) onSynced() }

    private suspend fun syncLocked(): SharedSyncResult = syncLock.withLock {
        val c = session.credentials ?: return@withLock SharedSyncResult(SharedSyncStatus.NOT_IN_USE)
        _state.update { it.copy(syncing = true) }
        val activity = mutableListOf<SharedActivity>()
        val skipped = mutableListOf<SharedError>()
        try {
            val error = push(c) ?: pull(c, activity, skipped) ?: skipped.firstOrNull()
            val result = when {
                error == null -> SharedSyncResult(SharedSyncStatus.DONE, null, activity)
                error.retryable -> SharedSyncResult(SharedSyncStatus.RETRY_LATER, error, activity)
                else -> SharedSyncResult(SharedSyncStatus.FAILED, error, activity)
            }
            if (error == null) {
                val now = clock()
                session.lastSyncAt = now
                _state.value = SharedSyncState(syncing = false, lastError = null, lastSuccessAt = now)
            } else {
                _state.update { it.copy(syncing = false, lastError = error) }
            }
            result
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    /** Clears the local copy (a new server address) and runs [then], with no sync in between. */
    suspend fun clearLocal(then: () -> Unit) = syncLock.withLock {
        store.transaction { store.clearAll() }
        then()
        _state.value = SharedSyncState()
    }

    /** Drops [groupId] and all its rows from this phone (after leaving or deleting it). */
    suspend fun removeGroupLocally(groupId: String) = store.transaction { store.removeGroup(groupId) }

    // ── Push ──

    /** Sends PENDING items oldest first. Returns the error that stopped it, or null when nothing is left to send. */
    private suspend fun push(c: SharedCredentials): SharedError? {
        val sends = HashMap<Long, Int>()
        while (true) {
            val item = store.outbox().firstOrNull {
                it.status == SharedOutboxStatus.PENDING && (sends[it.id] ?: 0) < MAX_SENDS_PER_RUN
            } ?: return null
            sends[item.id] = (sends[item.id] ?: 0) + 1
            send(c, item)?.let { return it }
        }
    }

    private suspend fun send(c: SharedCredentials, queued: SharedOutboxItem): SharedError? {
        val item = store.transaction {
            val current = store.outboxItem(queued.id)
            if (current == null || current.status != SharedOutboxStatus.PENDING) {
                null
            } else {
                current.copy(attempted = true).also { if (!current.attempted) store.updateOutbox(it) }
            }
        } ?: return null
        val local = store.entity(item.entityType, item.entityId)
        if (local == null) {
            store.transaction { store.deleteOutbox(item.id) }
            return null
        }
        val result: RemoteResult<SharedEntity?> = when (item.operation) {
            SharedOperation.SAVE ->
                if (item.restoreFirst && item.entityType == SharedEntityType.EXPENSE) {
                    when (val restored = remote.restoreExpense(c, item.groupId, item.entityId)) {
                        is RemoteResult.Ok -> {
                            rebase(item, SharedEntity.Expense(restored.value))
                            return null
                        }
                        is RemoteResult.Conflict -> RemoteResult.Conflict(SharedEntity.Expense(restored.current))
                        is RemoteResult.Err -> restored
                    }
                } else {
                    put(c, local, item.baseVersion)
                }
            SharedOperation.DELETE -> when (item.entityType) {
                SharedEntityType.EXPENSE -> remote.deleteExpense(c, item.groupId, item.entityId)
                SharedEntityType.SETTLEMENT -> remote.deleteSettlement(c, item.groupId, item.entityId)
                SharedEntityType.RECURRING -> remote.deleteRecurring(c, item.groupId, item.entityId)
            }.map { null }
            SharedOperation.RESTORE -> remote.restoreExpense(c, item.groupId, item.entityId).map { SharedEntity.Expense(it) }
        }
        return when (result) {
            is RemoteResult.Ok -> {
                complete(item, result.value)
                null
            }
            is RemoteResult.Conflict -> {
                val current = result.current
                when {
                    current == null -> markFailed(item, SharedError.Server(409, SharedServerCode.CONFLICT, "Conflict"))
                    // A repeated edit: the server already holds exactly what was sent.
                    SharedCanonical.same(local, current) -> complete(item, current)
                    else -> markConflict(item, current)
                }
                null
            }
            is RemoteResult.Err -> {
                val error = result.error
                when {
                    error.retryable || (error is SharedError.Server && error.status == 401) -> return error
                    // Deleting something the server does not have: it is gone either way.
                    item.operation == SharedOperation.DELETE && error is SharedError.Server && error.status == 404 -> completeGone(item)
                    else -> markFailed(item, error)
                }
                null
            }
        }
    }

    private suspend fun put(c: SharedCredentials, local: SharedEntity, baseVersion: Int): RemoteResult<SharedEntity?> = when (local) {
        is SharedEntity.Expense -> remote.putExpense(c, local.value, baseVersion).map { SharedEntity.Expense(it) }
        is SharedEntity.Settlement -> remote.putSettlement(c, local.value, baseVersion).map { SharedEntity.Settlement(it) }
        is SharedEntity.Recurring -> remote.putRecurring(c, local.value, baseVersion).map { SharedEntity.Recurring(it) }
    }

    /** The server accepted [sent]: drop the item and store its row, unless the user changed the row meanwhile. */
    private suspend fun complete(sent: SharedOutboxItem, row: SharedEntity?) = store.transaction {
        val current = store.outboxItem(sent.id) ?: return@transaction
        if (current.revision == sent.revision) {
            store.deleteOutbox(current.id)
            if (row != null) {
                store.writeEntity(row, pending = false)
            } else {
                store.entity(current.entityType, current.entityId)?.let { store.writeEntity(it, pending = false) }
            }
        } else {
            rebaseLocked(current, row)
        }
    }

    /** Keeps the queued change but bases it on the server's [row]. */
    private suspend fun rebase(sent: SharedOutboxItem, row: SharedEntity) = store.transaction {
        store.outboxItem(sent.id)?.let { rebaseLocked(it, row) }
    }

    private suspend fun rebaseLocked(current: SharedOutboxItem, row: SharedEntity?) {
        store.updateOutbox(
            current.copy(
                baseVersion = row?.version ?: current.baseVersion,
                restoreFirst = current.restoreFirst && (row == null || row.deletedAt != null),
                serverJson = row?.let(SharedEntityCodec::encode) ?: current.serverJson,
            ),
        )
    }

    private suspend fun completeGone(sent: SharedOutboxItem) = store.transaction {
        val current = store.outboxItem(sent.id) ?: return@transaction
        if (current.revision != sent.revision) return@transaction
        store.deleteOutbox(current.id)
        store.deleteEntity(current.entityType, current.entityId)
    }

    private suspend fun markConflict(sent: SharedOutboxItem, theirs: SharedEntity) = store.transaction {
        val current = store.outboxItem(sent.id) ?: return@transaction
        val withTheirs = current.copy(serverJson = SharedEntityCodec.encode(theirs))
        store.updateOutbox(
            if (current.operation == SharedOperation.SAVE) withTheirs.copy(status = SharedOutboxStatus.CONFLICT).withError(null) else withTheirs,
        )
    }

    private suspend fun markFailed(sent: SharedOutboxItem, error: SharedError) = store.transaction {
        val current = store.outboxItem(sent.id) ?: return@transaction
        // Changed since it was sent: the new version gets its own try.
        if (current.revision != sent.revision) return@transaction
        store.updateOutbox(current.copy(status = SharedOutboxStatus.FAILED).withError(error))
    }

    // ── Pull ──

    /** Returns the error that stopped the pull; errors that only skip one group go into [skipped]. */
    private suspend fun pull(c: SharedCredentials, newActivity: MutableList<SharedActivity>, skipped: MutableList<SharedError>): SharedError? {
        val refs = when (val r = remote.myGroups(c)) {
            is RemoteResult.Ok -> r.value
            is RemoteResult.Err -> return r.error
            is RemoteResult.Conflict -> return SharedError.Network("Unexpected answer from the server")
        }
        val listed = refs.map { it.id }.toSet()
        store.groupIds().filter { it !in listed }.forEach { id -> store.transaction { store.removeGroup(id) } }
        for (ref in refs) {
            val error = pullGroup(c, ref, newActivity) ?: continue
            if (error.retryable || (error is SharedError.Server && error.status == 401)) return error
            skipped += error
        }
        return null
    }

    private suspend fun pullGroup(c: SharedCredentials, ref: RemoteGroupRef, newActivity: MutableList<SharedActivity>): SharedError? {
        var cursor = store.groupCursor(ref.id) ?: 0L
        val firstDownload = cursor == 0L
        if (ref.rev <= cursor) return null
        while (true) {
            val page = when (val r = remote.changes(c, ref.id, cursor)) {
                is RemoteResult.Ok -> r.value
                is RemoteResult.Conflict -> return SharedError.Network("Unexpected answer from the server")
                is RemoteResult.Err -> {
                    val error = r.error
                    if (error is SharedError.Server && (error.status == 403 || error.status == 404)) {
                        store.transaction { store.removeGroup(ref.id) }
                        return null
                    }
                    return error
                }
            }
            store.transaction { apply(ref.id, page) }
            if (!firstDownload) newActivity += page.activity.filter { it.actorMemberId != page.me }
            if (page.group?.deletedAt != null) {
                store.transaction { store.removeGroup(ref.id) }
                return null
            }
            if (!page.hasMore || page.rev <= cursor) return null
            cursor = page.rev
        }
    }

    private suspend fun apply(groupId: String, page: RemoteChanges) {
        page.group?.let { store.upsertGroup(it.copy(myMemberId = page.me)) }
        page.members.forEach { store.upsertMember(it) }
        page.expenses.forEach { applyRow(SharedEntity.Expense(it)) }
        page.settlements.forEach { applyRow(SharedEntity.Settlement(it)) }
        page.recurring.forEach { applyRow(SharedEntity.Recurring(it)) }
        page.activity.forEach { store.insertActivity(it) }
        store.setGroupCursor(groupId, page.rev, page.me)
    }

    /** A server row never overwrites a row with an unsent change; it becomes that change's known server copy. */
    private suspend fun applyRow(row: SharedEntity) {
        val queued = store.outboxFor(row.type, row.id)
        if (queued == null) {
            store.writeEntity(row, pending = false)
        } else {
            store.updateOutbox(queued.copy(serverJson = SharedEntityCodec.encode(row)))
        }
    }

    // ── Local changes ──

    /** Writes [entity] (create or edit) as pending and queues it, coalescing with an unsent change to the same row. */
    suspend fun queueSave(entity: SharedEntity) = store.transaction {
        val queued = store.outboxFor(entity.type, entity.id)
        val stored = store.entity(entity.type, entity.id)
        store.writeEntity(entity, pending = true)
        if (queued == null) {
            store.insertOutbox(
                SharedOutboxItem(
                    groupId = entity.groupId, entityType = entity.type, entityId = entity.id,
                    operation = SharedOperation.SAVE, baseVersion = stored?.version ?: 0,
                    serverJson = stored?.let(SharedEntityCodec::encode), createdAt = clock(),
                ),
            )
        } else {
            val next = when (queued.operation) {
                SharedOperation.SAVE -> queued
                SharedOperation.RESTORE -> queued.copy(operation = SharedOperation.SAVE, restoreFirst = true)
                SharedOperation.DELETE -> queued.copy(
                    operation = SharedOperation.SAVE,
                    restoreFirst = queued.attempted && entity.type == SharedEntityType.EXPENSE,
                )
            }
            // A conflict stays one until the user resolves it; a failed change gets another try.
            val conflict = queued.status == SharedOutboxStatus.CONFLICT
            store.updateOutbox(
                next.copy(revision = queued.revision + 1, status = if (conflict) SharedOutboxStatus.CONFLICT else SharedOutboxStatus.PENDING)
                    .let { if (conflict) it else it.withError(null) },
            )
        }
    }

    /** Deletes the row on this phone at once and queues the delete. False when the row is not stored. */
    suspend fun queueDelete(type: SharedEntityType, id: String): Boolean = store.transaction {
        val stored = store.entity(type, id) ?: return@transaction false
        val queued = store.outboxFor(type, id)
        val deleted = stored.withDeletedAt(stored.deletedAt ?: clock())
        when {
            queued == null -> if (stored.deletedAt == null) {
                store.writeEntity(deleted, pending = true)
                store.insertOutbox(
                    SharedOutboxItem(
                        groupId = stored.groupId, entityType = type, entityId = id, operation = SharedOperation.DELETE,
                        baseVersion = stored.version, serverJson = SharedEntityCodec.encode(stored), createdAt = clock(),
                    ),
                )
            }
            // Created here and never sent: the server never knew it.
            queued.operation == SharedOperation.SAVE && queued.baseVersion == 0 && !queued.attempted -> {
                store.deleteOutbox(queued.id)
                store.deleteEntity(type, id)
            }
            // A restore that was never sent: back to the server's deleted copy.
            queued.operation == SharedOperation.RESTORE && !queued.attempted -> {
                store.deleteOutbox(queued.id)
                store.writeEntity(SharedEntityCodec.decode(type, queued.serverJson) ?: deleted, pending = false)
            }
            queued.operation == SharedOperation.DELETE -> Unit
            else -> {
                store.writeEntity(deleted, pending = true)
                store.updateOutbox(
                    queued.copy(
                        operation = SharedOperation.DELETE, restoreFirst = false, revision = queued.revision + 1,
                        status = SharedOutboxStatus.PENDING,
                    ).withError(null),
                )
            }
        }
        true
    }

    /** Restores a deleted expense on this phone at once and queues the restore. False when it is not stored. */
    suspend fun queueRestore(expenseId: String): Boolean = store.transaction {
        val stored = store.expense(expenseId) ?: return@transaction false
        if (stored.deletedAt == null) return@transaction true
        val queued = store.outboxFor(SharedEntityType.EXPENSE, expenseId)
        val live = SharedEntity.Expense(stored.copy(deletedAt = null))
        val serverCopy = SharedEntityCodec.decode(SharedEntityType.EXPENSE, queued?.serverJson)
        when {
            queued == null -> {
                store.writeEntity(live, pending = true)
                store.insertOutbox(
                    SharedOutboxItem(
                        groupId = stored.groupId, entityType = SharedEntityType.EXPENSE, entityId = expenseId,
                        operation = SharedOperation.RESTORE, baseVersion = stored.version,
                        serverJson = SharedEntityCodec.encode(SharedEntity.Expense(stored)), createdAt = clock(),
                    ),
                )
            }
            // A delete that was never sent: the server still has the live copy.
            queued.operation == SharedOperation.DELETE && !queued.attempted && serverCopy != null && serverCopy.deletedAt == null -> {
                store.deleteOutbox(queued.id)
                store.writeEntity(serverCopy, pending = false)
            }
            else -> {
                store.writeEntity(live, pending = true)
                store.updateOutbox(
                    queued.copy(operation = SharedOperation.RESTORE, revision = queued.revision + 1, status = SharedOutboxStatus.PENDING)
                        .withError(null),
                )
            }
        }
        true
    }

    /**
     * Settles a conflict. Keeping mine sends the user's version again based on
     * the server's current one; using theirs drops the local change.
     */
    suspend fun resolveConflict(itemId: Long, keepMine: Boolean): SharedResult<Unit> = store.transaction {
        val item = store.outboxItem(itemId)
        val theirs = SharedEntityCodec.decode(item?.entityType ?: SharedEntityType.EXPENSE, item?.serverJson)
        if (item == null || item.status != SharedOutboxStatus.CONFLICT || theirs == null) {
            return@transaction SharedResult.Failure(SharedError.NotFound)
        }
        if (keepMine) {
            val deletedThere = theirs.deletedAt != null
            if (deletedThere && item.entityType != SharedEntityType.EXPENSE) return@transaction SharedResult.Failure(SharedError.DeletedElsewhere)
            store.updateOutbox(
                item.copy(
                    status = SharedOutboxStatus.PENDING, baseVersion = theirs.version, restoreFirst = deletedThere,
                    revision = item.revision + 1,
                ).withError(null),
            )
        } else {
            store.deleteOutbox(item.id)
            store.writeEntity(theirs, pending = false)
        }
        SharedResult.Success(Unit)
    }

    /** Drops a queued change (normally a failed one) and puts back the server's copy of the row, if there is one. */
    suspend fun discard(itemId: Long): SharedResult<Unit> = store.transaction {
        val item = store.outboxItem(itemId) ?: return@transaction SharedResult.Failure(SharedError.NotFound)
        store.deleteOutbox(item.id)
        val serverCopy = SharedEntityCodec.decode(item.entityType, item.serverJson)
        if (serverCopy != null) store.writeEntity(serverCopy, pending = false) else store.deleteEntity(item.entityType, item.entityId)
        SharedResult.Success(Unit)
    }

    private companion object {
        /** A row can need a restore, then a PUT, then a resend after an edit in flight. */
        const val MAX_SENDS_PER_RUN = 3
    }
}

internal fun <T, R> RemoteResult<T>.map(f: (T) -> R): RemoteResult<R> = when (this) {
    is RemoteResult.Ok -> RemoteResult.Ok(f(value))
    is RemoteResult.Conflict -> RemoteResult.Conflict(f(current))
    is RemoteResult.Err -> this
}

internal fun SharedEntity.withDeletedAt(at: Long?): SharedEntity = when (this) {
    is SharedEntity.Expense -> copy(value = value.copy(deletedAt = at))
    is SharedEntity.Settlement -> copy(value = value.copy(deletedAt = at))
    is SharedEntity.Recurring -> copy(value = value.copy(deletedAt = at))
}

/** "Is this what I sent?": the fields a phone sends, compared the way the server compares them. */
internal object SharedCanonical {
    fun same(sent: SharedEntity, current: SharedEntity): Boolean = current.deletedAt == null && key(sent) == key(current)

    private fun amount(value: BigDecimal) = value.stripTrailingZeros().toPlainString()

    private fun text(value: String?) = value?.trim().orNullIfBlank()

    private fun key(entity: SharedEntity): List<Any?> = when (entity) {
        is SharedEntity.Expense -> entity.value.let { e ->
            listOf(
                e.description.trim(), amount(e.amount), e.currency, e.date, text(e.category), e.notes.orNullIfBlank(),
                e.splitMethod, e.recurringId,
                e.payers.sortedBy { it.memberId }.map { it.memberId to amount(it.amount) },
                e.shares.sortedBy { it.memberId }.map { Triple(it.memberId, amount(it.amount), it.weight) },
            )
        }
        is SharedEntity.Settlement -> entity.value.let { s ->
            listOf(s.fromMemberId, s.toMemberId, amount(s.amount), s.currency, s.date, s.notes.orNullIfBlank())
        }
        is SharedEntity.Recurring -> entity.value.let { r ->
            val t = r.template
            listOf(
                t.description.trim(), amount(t.amount), t.currency, text(t.category), t.notes.orNullIfBlank(), t.splitMethod,
                t.payers.sortedBy { it.memberId }.map { it.memberId to amount(it.amount) },
                t.shares.sortedBy { it.memberId }.map { Triple(it.memberId, amount(it.amount), it.weight) },
                r.interval, r.nextDate, r.anchorDay,
            )
        }
    }
}
