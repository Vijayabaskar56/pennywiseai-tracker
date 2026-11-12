package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSplit
import com.pennywiseai.tracker.domain.sharedexpenses.SplitInput
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.domain.sharedexpenses.SplitResult
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Two phones against a real server (`cd shared-expenses && npm run dev`). Skipped
 * unless SHARED_EXPENSES_TEST_URL is set, e.g. `http://localhost:8790`.
 */
class SharedExpensesLiveTest {
    private val url: String? = System.getenv("SHARED_EXPENSES_TEST_URL")?.takeIf { it.isNotBlank() }
    private val remote = SharedApiClient(OkHttp.create())

    private inner class Phone(val credentials: SharedCredentials) {
        val store = FakeSharedLocalStore()
        val sync = SharedSyncManager(store, remote, FakeSession(credentials)) { System.currentTimeMillis() }
    }

    @Before
    fun needsServer() = assumeTrue("SHARED_EXPENSES_TEST_URL is not set", url != null)

    private fun <T> RemoteResult<T>.ok(): T = (this as? RemoteResult.Ok)?.value ?: throw AssertionError("Expected success, got $this")

    private suspend fun register(): SharedCredentials = remote.register(SharedServerAddress.normalize(url!!)!!).ok()

    @Test
    fun `two phones share a group end to end`() = runBlocking {
        val a = Phone(register())
        val b = Phone(register())

        // Member A creates the group and a seat, then invites someone into that seat.
        val created = remote.createGroup(a.credentials, "Flat", "INR", "Member A").ok()
        val groupId = created.group.id
        val meA = created.group.myMemberId!!
        a.store.upsertGroup(created.group)
        created.members.forEach { a.store.upsertMember(it) }
        val seat = remote.addMember(a.credentials, groupId, "Roommate").ok()
        val invite = remote.createInvite(a.credentials, groupId, seat.id).ok()
        val link = SharedInviteLink.parse(invite.url)
        assertNotNull("invite url parses: ${invite.url}", link)

        // Member B previews and joins.
        val preview = remote.previewInvite(b.credentials, link!!.token).ok()
        assertEquals("Flat", preview.groupName)
        assertEquals("Roommate", preview.memberDisplayName)
        val joined = remote.acceptInvite(b.credentials, link.token, "Member B").ok()
        assertEquals(SharedJoin(groupId, seat.id), joined)
        val meB = seat.id

        assertEquals(SharedSyncStatus.DONE, a.sync.sync().status)
        assertEquals(SharedSyncStatus.DONE, b.sync.sync().status)
        assertEquals(meB, b.store.groups.getValue(groupId).myMemberId)
        assertEquals(setOf("Member A", "Member B"), b.store.members.values.map { it.displayName }.toSet())

        // An expense from A reaches B.
        val shares = (SharedSplit.split(BigDecimal("1000.00"), "INR", SplitInput.Equal(setOf(meA, meB))) as SplitResult.Success).shares
        val expenseId = UUID.randomUUID().toString()
        val expense = SharedExpense(
            id = expenseId, groupId = groupId, description = "Groceries", amount = BigDecimal("1000.00"), currency = "INR",
            date = LocalDate.of(2026, 11, 10), category = "Groceries", splitMethod = SplitMethod.EQUAL,
            payers = listOf(PayerPart(meA, BigDecimal("1000.00"))), shares = shares,
            createdBy = meA, updatedBy = meA, createdAt = 0, updatedAt = 0, version = 0, rev = 0, pending = true,
        )
        a.sync.queueSave(SharedEntity.Expense(expense))
        assertEquals(SharedSyncStatus.DONE, a.sync.sync().status)
        assertEquals(1, a.store.expenses.getValue(expenseId).version)
        val bSees = b.sync.sync()
        assertEquals(BigDecimal("500.00"), b.store.expenses.getValue(expenseId).shares.single { it.memberId == meB }.amount)
        assertTrue(bSees.newActivityByOthers.any { it.type == ActivityType.EXPENSE_ADDED && it.actorMemberId == meA })

        // B still owes 500, so the seat cannot be removed yet.
        val blocked = remote.removeMember(a.credentials, groupId, meB)
        assertEquals(SharedServerCode.BALANCE_NOT_ZERO, ((blocked as RemoteResult.Err).error as SharedError.Server).code)

        // B settles up; A sees it.
        val settlementId = UUID.randomUUID().toString()
        b.sync.queueSave(
            SharedEntity.Settlement(
                SharedSettlement(
                    id = settlementId, groupId = groupId, fromMemberId = meB, toMemberId = meA, amount = BigDecimal("500.00"),
                    currency = "INR", date = LocalDate.of(2026, 12, 5), createdBy = meB, updatedBy = meB, createdAt = 0,
                    updatedAt = 0, version = 0, rev = 0, pending = true,
                ),
            ),
        )
        assertEquals(SharedSyncStatus.DONE, b.sync.sync().status)
        a.sync.sync()
        assertEquals(BigDecimal("500.00"), a.store.settlements.getValue(settlementId).amount)

        // Both edit the expense from version 1: A first, so B's edit conflicts.
        a.sync.queueSave(SharedEntity.Expense(a.store.expenses.getValue(expenseId).copy(description = "Groceries and fruit")))
        b.sync.queueSave(SharedEntity.Expense(b.store.expenses.getValue(expenseId).copy(category = "Food")))
        assertEquals(SharedSyncStatus.DONE, a.sync.sync().status)
        b.sync.sync()
        val conflict = b.store.outboxItems.values.single()
        assertEquals(SharedOutboxStatus.CONFLICT, conflict.status)
        val theirs = SharedEntityCodec.decode(SharedEntityType.EXPENSE, conflict.serverJson) as SharedEntity.Expense
        assertEquals("Groceries and fruit", theirs.value.description)
        assertEquals("Groceries", b.store.expenses.getValue(expenseId).description)

        // B keeps their version; it goes up on top of A's.
        assertEquals(SharedResult.Success(Unit), b.sync.resolveConflict(conflict.id, keepMine = true))
        assertEquals(SharedSyncStatus.DONE, b.sync.sync().status)
        assertTrue(b.store.outboxItems.isEmpty())
        assertEquals(3, b.store.expenses.getValue(expenseId).version)
        a.sync.sync()
        assertEquals("Food", a.store.expenses.getValue(expenseId).category)
        assertEquals("Groceries", a.store.expenses.getValue(expenseId).description)

        // Everyone is square now, so A removes B's seat and B's phone drops the group.
        assertEquals(Unit, remote.removeMember(a.credentials, groupId, meB).ok())
        a.sync.sync()
        assertTrue(a.store.members.getValue(meB).removedAt != null)
        assertEquals(SharedSyncStatus.DONE, b.sync.sync().status)
        assertTrue(b.store.groups.isEmpty())
        assertTrue(b.store.expenses.isEmpty())
        assertTrue(b.store.settlements.isEmpty())
    }
}
