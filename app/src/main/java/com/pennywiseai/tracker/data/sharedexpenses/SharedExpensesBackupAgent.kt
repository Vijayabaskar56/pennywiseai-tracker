package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.backup.SharedDismissedEntryBackup
import com.pennywiseai.tracker.data.backup.SharedExpensesBackup
import com.pennywiseai.tracker.data.backup.SharedExpensesBackupPort
import com.pennywiseai.tracker.data.backup.SharedIdentityBackup
import com.pennywiseai.tracker.data.backup.SharedLinkBackup
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where a restored identity goes; [SharedIdentity] in the app. */
interface SharedIdentityRestore {
    /** The registration in use, or null. */
    fun current(): SharedIdentityBackup?

    /** Takes [identity] only when this phone has no registration; true when taken. */
    fun restore(backup: SharedIdentityBackup): Boolean
}

/**
 * The Shared expenses section of a PennyWise backup (docs/backup-format.md):
 * the identity, the links (by transaction hash) and the entries the user
 * deleted. The cached groups and the outbox are not backed up.
 */
class SharedExpensesBackupCore(
    private val identity: SharedIdentityRestore,
    private val store: SharedLinkStore,
    private val ledger: BudgetLedger,
    private val budget: SharedBudgetLink,
    private val clock: () -> Long,
    /** After a restore: register the periodic sync and sync soon. */
    private val syncAfterRestore: () -> Unit,
) : SharedExpensesBackupPort {

    override suspend fun export(): SharedExpensesBackup? {
        val links = store.links().filter { it.entityType != SharedEntityType.RECURRING }.map {
            SharedLinkBackup(it.groupId, it.entityType.name, it.entityId, it.transactionHash, it.original, it.createdAt)
        }
        val dismissed = store.projections().filter { ledger.liveByHash(it.transactionHash) == null }
            .map { SharedDismissedEntryBackup(it.groupId, it.expenseId) }
        val me = identity.current()
        if (me == null && links.isEmpty() && dismissed.isEmpty()) return null
        return SharedExpensesBackup(identity = me, links = links, dismissedEntries = dismissed)
    }

    override suspend fun restore(section: SharedExpensesBackup?, replaceAll: Boolean) {
        if (replaceAll) forgetReplaced()
        val restoredIdentity = section?.identity?.let { identity.restore(it) } == true
        section?.let { restoreLinks(it) }
        // Entries (`shared:` hashes) came back as ordinary transactions; the reconcile adopts them by hash.
        budget.reconcile()
        if (restoredIdentity || identity.current() != null) syncAfterRestore()
    }

    /**
     * REPLACE_ALL swapped every transaction, keeping the backup's row IDs: this
     * phone's links could now name other rows, and its record of deleted
     * entries no longer matches. The backup's links and dismissals replace them.
     */
    private suspend fun forgetReplaced() {
        store.links().forEach { store.deleteLink(it.id) }
        store.projections().filter { ledger.liveByHash(it.transactionHash) == null }.forEach { store.deleteProjection(it.expenseId) }
    }

    private suspend fun restoreLinks(section: SharedExpensesBackup) {
        val existing = store.links()
        val takenTransactions = existing.map { it.transactionId }.toMutableSet()
        val linkedEntities = existing.map { it.entityType to it.entityId }.toMutableSet()
        for (b in section.links) {
            val type = runCatching { SharedEntityType.valueOf(b.entityType) }.getOrNull()
            if (type == null || type == SharedEntityType.RECURRING || b.entityId.isBlank()) continue
            val tx = ledger.liveByHash(b.transactionHash) ?: continue // that transaction did not come back
            if (tx.id in takenTransactions || (type to b.entityId) in linkedEntities) continue // never a duplicate link
            store.insertLink(
                SharedLink(
                    groupId = b.groupId, entityType = type, entityId = b.entityId, transactionId = tx.id,
                    transactionHash = tx.transactionHash, original = b.originalState, createdAt = b.createdAt.takeIf { it > 0 } ?: clock(),
                ),
            )
            takenTransactions += tx.id
            linkedEntities += type to b.entityId
        }
        val projected = store.projections().map { it.expenseId }.toSet()
        for (d in section.dismissedEntries) {
            if (d.expenseId.isBlank() || d.expenseId in projected) continue
            val hash = SharedBudgetLink.entryHash(d.expenseId)
            if (ledger.liveByHash(hash) != null) continue
            // Only its presence matters: a projection with no live entry is a deleted entry.
            store.upsertProjection(
                SharedProjection(d.expenseId, d.groupId, hash, BigDecimal.ZERO, "", LocalDate.EPOCH, null, "", clock()),
            )
        }
    }
}

@Singleton
class SharedExpensesBackupAgent @Inject constructor(
    identity: SharedIdentity,
    store: SharedLinkStore,
    ledger: BudgetLedger,
    budget: SharedBudgetLink,
    scheduler: SharedSyncScheduler,
) : SharedExpensesBackupPort by SharedExpensesBackupCore(
    identity = object : SharedIdentityRestore {
        override fun current(): SharedIdentityBackup? = identity.credentials?.let {
            SharedIdentityBackup(it.baseUrl, it.userId, it.secret, identity.displayName)
        }

        override fun restore(backup: SharedIdentityBackup): Boolean =
            identity.restoreRegistration(backup.serverAddress, backup.userId, backup.secret, backup.displayName)
    },
    store = store,
    ledger = ledger,
    budget = budget,
    clock = System::currentTimeMillis,
    syncAfterRestore = {
        scheduler.ensurePeriodic()
        scheduler.syncSoon()
    },
)
