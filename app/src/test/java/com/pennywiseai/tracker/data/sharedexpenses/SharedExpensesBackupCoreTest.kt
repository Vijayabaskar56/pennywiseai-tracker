package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.backup.SharedDismissedEntryBackup
import com.pennywiseai.tracker.data.backup.SharedExpensesBackup
import com.pennywiseai.tracker.data.backup.SharedIdentityBackup
import com.pennywiseai.tracker.data.backup.SharedLinkBackup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.expense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.payers
import com.pennywiseai.tracker.domain.sharedexpenses.SharedFixtures.shares
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** The Shared expenses part of backup and restore. Synthetic values only. */
class SharedExpensesBackupCoreTest {
    private val store = FakeSharedLinkStore()
    private val ledger = FakeBudgetLedger()
    private val budget = SharedBudgetLink(store, ledger) { 1_000L }
    private var identity: SharedIdentityBackup? = null
    private var syncs = 0
    private val core = SharedExpensesBackupCore(
        identity = object : SharedIdentityRestore {
            override fun current() = identity
            override fun restore(backup: SharedIdentityBackup): Boolean {
                if (identity != null) return false
                identity = backup
                return true
            }
        },
        store = store,
        ledger = ledger,
        budget = budget,
        clock = { 2_000L },
        syncAfterRestore = { syncs++ },
    )

    private val mine = SharedIdentityBackup("https://shared.example.com", "user-1", "secret-1", "Member A")

    @Before
    fun setUp() {
        store.memberIds["g1"] = "a"
    }

    @Test
    fun `nothing to export when the feature was never used`() = runBlocking {
        assertNull(core.export())
    }

    @Test
    fun `export holds the identity, the links by hash and deleted entries`() = runBlocking {
        identity = mine
        val e = expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        val other = expense(payers("b" to "100"), shares("a" to "50", "b" to "50")).also { store.expenses[it.id] = it }
        ledger.add(1, "1000", hash = "sms-1")
        budget.linkExpense(1, e.id)
        ledger.softDelete(ledger.liveByHash(SharedBudgetLink.entryHash(other.id))!!.id)

        val section = core.export()!!
        assertEquals(mine, section.identity)
        assertEquals(listOf(SharedLinkBackup("g1", "EXPENSE", e.id, "sms-1", null, 1_000L)), section.links)
        assertEquals(listOf(SharedDismissedEntryBackup("g1", other.id)), section.dismissedEntries)
    }

    @Test
    fun `restore links each transaction by hash, skips missing ones and never duplicates`() = runBlocking {
        val e = expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        ledger.add(50, "1000", hash = "sms-1") // the restored row, under a new id
        val section = SharedExpensesBackup(
            links = listOf(
                SharedLinkBackup("g1", "EXPENSE", e.id, "sms-1"),
                SharedLinkBackup("g1", "EXPENSE", "e-gone", "sms-absent"),
                SharedLinkBackup("g1", "UNKNOWN", "x", "sms-1"),
            ),
        )
        core.restore(section, replaceAll = false)
        core.restore(section, replaceAll = false)

        assertEquals(50L, store.links().single().transactionId)
        assertEquals(0, BigDecimal("600").compareTo(ledger.byId(50)!!.lentSharesTotal))
    }

    @Test
    fun `restore keeps this phone's own registration`() = runBlocking {
        identity = mine
        core.restore(SharedExpensesBackup(identity = mine.copy(userId = "user-2", secret = "secret-2")), replaceAll = false)
        assertEquals("user-1", identity!!.userId)
    }

    @Test
    fun `restore takes the identity on a phone with none and syncs`() = runBlocking {
        core.restore(SharedExpensesBackup(identity = mine), replaceAll = false)
        assertEquals(mine, identity)
        assertEquals(1, syncs)
    }

    @Test
    fun `an older backup with no section only reconciles`() = runBlocking {
        val e = expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        core.restore(null, replaceAll = false)
        assertNull(identity)
        assertEquals(0, syncs)
        assertTrue(ledger.liveByHash(SharedBudgetLink.entryHash(e.id)) != null)
    }

    @Test
    fun `a restored entry is adopted by hash, not created twice`() = runBlocking {
        val e = expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        ledger.add(60, "400", hash = SharedBudgetLink.entryHash(e.id))
        core.restore(SharedExpensesBackup(), replaceAll = true)
        assertEquals(1, ledger.rows.size)
        assertEquals(e.id, store.projectionRows.keys.single())
    }

    @Test
    fun `an entry deleted before the backup stays deleted after restore`() = runBlocking {
        val e = expense(payers("b" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        core.restore(SharedExpensesBackup(dismissedEntries = listOf(SharedDismissedEntryBackup("g1", e.id))), replaceAll = true)
        assertNull(ledger.liveByHash(SharedBudgetLink.entryHash(e.id)))
        assertTrue(ledger.rows.isEmpty())
    }

    @Test
    fun `replace all drops this phone's old links before taking the backup's`() = runBlocking {
        val e = expense(payers("a" to "1000"), shares("a" to "400", "b" to "600")).also { store.expenses[it.id] = it }
        ledger.add(1, "1000", hash = "sms-old")
        budget.linkExpense(1, e.id)
        ledger.rows.clear()
        ledger.add(1, "1000", hash = "sms-new") // REPLACE_ALL reused the id for another row
        core.restore(SharedExpensesBackup(), replaceAll = true)
        assertTrue(store.links().isEmpty())
        assertNull(ledger.byId(1)!!.lentSharesTotal)
        assertFalse(ledger.liveByHash(SharedBudgetLink.entryHash(e.id)) == null)
    }
}
