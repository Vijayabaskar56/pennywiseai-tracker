package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.sharedexpenses.ScriptedServer.Reply
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.A
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.B
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.G
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.activity
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.changes
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.error
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.expense
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.expenseJson
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.group
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.id
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.member
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.myGroups
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.recurring
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.recurringJson
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.settlement
import com.pennywiseai.tracker.data.sharedexpenses.SharedSamples.settlementJson
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SharedSyncManagerTest {
    private val store = FakeSharedLocalStore()
    private val server = ScriptedServer()
    private val session = FakeSession()
    private val manager = SharedSyncManager(store, SharedApiClient(server.engine), session) { 1_000L }

    private val e1 = id(1)
    private val e2 = id(2)
    private val s1 = id(3)
    private val r1 = id(4)

    /** A group already pulled up to [rev] with Member A (this phone) and Member B; `me/groups` says nothing moved. */
    private suspend fun seedGroup(rev: Long = 5) {
        store.upsertGroup(group(rev))
        store.setGroupCursor(G, rev, A)
        store.upsertMember(member(A, "Member A"))
        store.upsertMember(member(B, "Member B"))
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(rev)) }
    }

    private fun item(id: String) = store.outboxItems.values.single { it.entityId == id }

    private fun putPath(kind: String, id: String) = "/v1/groups/$G/$kind/$id"

    private fun d(v: String) = BigDecimal(v)

    // ── Push ──

    @Test
    fun `pushes the outbox oldest first and stores the server's rows`() = runBlocking {
        seedGroup()
        manager.queueSave(SharedEntity.Expense(expense(e1, version = 0, rev = 0, pending = true)))
        manager.queueSave(SharedEntity.Settlement(settlement(s1, version = 0, rev = 0)))
        manager.queueSave(SharedEntity.Expense(expense(e2, version = 0, rev = 0, pending = true)))
        assertTrue(store.expenses.getValue(e1).pending)
        server.on("PUT", putPath("expenses", e1)) { Reply(201, """{"expense":${expenseJson(expense(e1, rev = 6))}}""") }
        server.on("PUT", putPath("settlements", s1)) { Reply(201, """{"settlement":${settlementJson(settlement(s1, rev = 7))}}""") }
        server.on("PUT", putPath("expenses", e2)) { Reply(201, """{"expense":${expenseJson(expense(e2, rev = 8))}}""") }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.DONE, result.status)
        assertEquals(listOf(putPath("expenses", e1), putPath("settlements", s1), putPath("expenses", e2)), server.writes.map { it.path })
        assertTrue(server.writes.all { it.body!!.contains("\"baseVersion\":0") })
        assertTrue(server.writes.all { it.auth == "Device ${FakeSession.CREDENTIALS.userId}.${FakeSession.CREDENTIALS.secret}" })
        assertTrue(store.outboxItems.isEmpty())
        assertFalse(store.expenses.getValue(e1).pending)
        assertEquals(6L, store.expenses.getValue(e1).rev)
        assertEquals(1, store.settlements.getValue(s1).version)
        assertEquals(1_000L, session.lastSyncAt)
        assertEquals(1_000L, manager.state.value.lastSuccessAt)
    }

    @Test
    fun `a second edit before sending replaces the queued change and keeps its base version`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 3))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 3)))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "140.00", version = 3)))
        // A new expense edited before it was ever sent stays a create.
        manager.queueSave(SharedEntity.Expense(expense(e2, version = 0)))
        manager.queueSave(SharedEntity.Expense(expense(e2, amount = "60.00", version = 0)))

        assertEquals(2, store.outboxItems.size)
        assertEquals(3, item(e1).baseVersion)
        assertEquals(1, item(e1).revision)
        assertEquals(0, item(e2).baseVersion)

        server.on("PUT", putPath("expenses", e1)) { Reply(200, """{"expense":${expenseJson(expense(e1, amount = "140.00", version = 4))}}""") }
        server.on("PUT", putPath("expenses", e2)) { Reply(201, """{"expense":${expenseJson(expense(e2, amount = "60.00"))}}""") }
        manager.sync()

        assertEquals(2, server.writes.size)
        val first = server.writes[0].body!!
        assertTrue(first.contains("\"baseVersion\":3"))
        assertTrue(first.contains("\"amount\":\"140.00\""))
        assertTrue(server.writes[1].body!!.contains("\"baseVersion\":0"))
        assertTrue(server.writes[1].body!!.contains("\"amount\":\"60.00\""))
    }

    @Test
    fun `deleting a row whose create was never sent drops both`() = runBlocking {
        seedGroup()
        manager.queueSave(SharedEntity.Expense(expense(e1, version = 0)))
        assertTrue(manager.queueDelete(SharedEntityType.EXPENSE, e1))

        assertNull(store.expenses[e1])
        assertTrue(store.outboxItems.isEmpty())
        manager.sync()
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `deleting a synced row sends a DELETE`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 2))
        manager.queueDelete(SharedEntityType.EXPENSE, e1)
        assertNotNull(store.expenses.getValue(e1).deletedAt)
        assertTrue(store.expenses.getValue(e1).pending)
        server.on("DELETE", putPath("expenses", e1)) { Reply(204) }

        manager.sync()

        assertEquals(listOf("DELETE" to putPath("expenses", e1)), server.writes.map { it.method to it.path })
        assertTrue(store.outboxItems.isEmpty())
        assertNotNull(store.expenses.getValue(e1).deletedAt)
        assertFalse(store.expenses.getValue(e1).pending)
    }

    @Test
    fun `a repeated edit answered 409 with what was sent counts as sent`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        val stored = expense(e1, amount = "120.00", version = 2, rev = 6)
        server.on("PUT", putPath("expenses", e1)) { Reply(409, error("conflict", current = expenseJson(stored))) }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.DONE, result.status)
        assertTrue(store.outboxItems.isEmpty())
        assertEquals(stored, store.expenses.getValue(e1))
    }

    @Test
    fun `a conflict keeps my version and keep-mine sends it again on top of theirs`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        val theirs = expense(e1, amount = "200.00", description = "Fruit", version = 2, rev = 6)
        server.on("PUT", putPath("expenses", e1)) { Reply(409, error("conflict", current = expenseJson(theirs))) }

        manager.sync()

        val queued = item(e1)
        assertEquals(SharedOutboxStatus.CONFLICT, queued.status)
        assertEquals(d("120.00"), store.expenses.getValue(e1).amount)
        assertTrue(store.expenses.getValue(e1).pending)
        assertEquals(SharedEntity.Expense(theirs), SharedEntityCodec.decode(SharedEntityType.EXPENSE, queued.serverJson))

        // A conflict is not sent again until it is resolved.
        manager.sync()
        assertEquals(1, server.writes.size)

        assertEquals(SharedResult.Success(Unit), manager.resolveConflict(queued.id, keepMine = true))
        server.on("PUT", putPath("expenses", e1)) { Reply(200, """{"expense":${expenseJson(expense(e1, amount = "120.00", version = 3, rev = 7))}}""") }
        manager.sync()

        assertTrue(server.writes.last().body!!.contains("\"baseVersion\":2"))
        assertTrue(server.writes.last().body!!.contains("\"amount\":\"120.00\""))
        assertTrue(store.outboxItems.isEmpty())
        assertEquals(3, store.expenses.getValue(e1).version)
        assertFalse(store.expenses.getValue(e1).pending)
    }

    @Test
    fun `use theirs drops my change`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        val theirs = expense(e1, amount = "200.00", version = 2, rev = 6)
        server.on("PUT", putPath("expenses", e1)) { Reply(409, error("conflict", current = expenseJson(theirs))) }
        manager.sync()

        assertEquals(SharedResult.Success(Unit), manager.resolveConflict(item(e1).id, keepMine = false))

        assertEquals(theirs, store.expenses.getValue(e1))
        assertTrue(store.outboxItems.isEmpty())
        manager.sync()
        assertEquals(1, server.writes.size)
    }

    @Test
    fun `keep mine is refused for a settlement someone deleted`() = runBlocking {
        seedGroup()
        store.upsertSettlement(settlement(s1))
        manager.queueSave(SharedEntity.Settlement(settlement(s1, amount = "60.00")))
        val theirs = settlement(s1, version = 2, rev = 6).copy(deletedAt = 5)
        server.on("PUT", putPath("settlements", s1)) { Reply(409, error("conflict", current = settlementJson(theirs))) }
        manager.sync()

        assertEquals(SharedResult.Failure(SharedError.DeletedElsewhere), manager.resolveConflict(item(s1).id, keepMine = true))
    }

    @Test
    fun `refused changes are marked failed with the server's error and can be discarded`() = runBlocking {
        for ((status, code) in listOf(403 to "forbidden", 404 to "not_found", 422 to "invalid")) {
            val store = FakeSharedLocalStore()
            val server = ScriptedServer()
            val manager = SharedSyncManager(store, SharedApiClient(server.engine), FakeSession()) { 1_000L }
            store.upsertGroup(group(5))
            store.setGroupCursor(G, 5, A)
            server.on("GET", "/v1/me/groups") { Reply(200, myGroups(5)) }
            manager.queueSave(SharedEntity.Expense(expense(e1, version = 0)))
            server.on("PUT", ".*") { Reply(status, error(code, "Refused", field = "shares")) }

            val result = manager.sync()

            assertEquals(SharedSyncStatus.DONE, result.status)
            val failed = store.outboxItems.values.single()
            assertEquals(SharedOutboxStatus.FAILED, failed.status)
            assertEquals(SharedError.Server(status, SharedServerCode.fromWire(code, status), "Refused", "shares"), failed.error)
            // A failed item is not retried by itself.
            manager.sync()
            assertEquals(1, server.writes.size)

            assertEquals(SharedResult.Success(Unit), manager.discard(failed.id))
            assertNull("a refused create leaves nothing behind", store.expenses[e1])
            assertTrue(store.outboxItems.isEmpty())
        }
    }

    @Test
    fun `discarding a refused edit puts the server's copy back`() = runBlocking {
        seedGroup()
        val original = expense(e1, version = 1)
        store.upsertExpense(original)
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        server.on("PUT", putPath("expenses", e1)) { Reply(422, error("invalid", field = "payers")) }
        manager.sync()

        manager.discard(item(e1).id)

        assertEquals(original, store.expenses.getValue(e1))
    }

    @Test
    fun `network errors, rate limits and busy servers stop the push and keep every change`() = runBlocking {
        for (status in listOf(ScriptedServer.NETWORK_DOWN, 429, 500, 503)) {
            val store = FakeSharedLocalStore()
            val server = ScriptedServer()
            val manager = SharedSyncManager(store, SharedApiClient(server.engine), FakeSession()) { 1_000L }
            store.upsertGroup(group(5))
            store.setGroupCursor(G, 5, A)
            server.on("GET", "/v1/me/groups") { Reply(200, myGroups(5)) }
            manager.queueSave(SharedEntity.Expense(expense(e1, version = 0)))
            manager.queueSave(SharedEntity.Expense(expense(e2, version = 0)))
            server.on("PUT", ".*") { Reply(status, if (status > 0) error(if (status == 429) "rate_limited" else "busy") else "") }

            val result = manager.sync()

            assertEquals("status $status", SharedSyncStatus.RETRY_LATER, result.status)
            assertEquals("stops at the first item", 1, server.seen.size)
            assertTrue(store.outboxItems.values.all { it.status == SharedOutboxStatus.PENDING })
            assertEquals(2, store.outboxItems.size)
            assertTrue(store.expenses.getValue(e1).pending)
            assertNotNull(manager.state.value.lastError)
            assertFalse(manager.state.value.syncing)
        }
    }

    @Test
    fun `a 401 stops the sync as failed`() = runBlocking {
        seedGroup()
        manager.queueSave(SharedEntity.Expense(expense(e1, version = 0)))
        server.on("PUT", ".*") { Reply(401, error("unauthorized")) }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.FAILED, result.status)
        assertEquals(SharedOutboxStatus.PENDING, item(e1).status)
    }

    @Test
    fun `an edit made while the row is on its way is sent next, based on the server's answer`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        var puts = 0
        server.on("PUT", putPath("expenses", e1)) { seen ->
            puts++
            if (puts == 1) {
                manager.queueSave(SharedEntity.Expense(expense(e1, amount = "140.00", version = 1)))
                Reply(200, """{"expense":${expenseJson(expense(e1, amount = "120.00", version = 2))}}""")
            } else {
                assertTrue(seen.body!!.contains("\"baseVersion\":2"))
                assertTrue(seen.body!!.contains("\"amount\":\"140.00\""))
                Reply(200, """{"expense":${expenseJson(expense(e1, amount = "140.00", version = 3))}}""")
            }
        }

        manager.sync()

        assertEquals(2, puts)
        assertEquals(d("140.00"), store.expenses.getValue(e1).amount)
        assertEquals(3, store.expenses.getValue(e1).version)
        assertTrue(store.outboxItems.isEmpty())
    }

    @Test
    fun `restoring then editing a deleted expense restores it before the edit`() = runBlocking {
        seedGroup()
        store.upsertExpense(expense(e1, version = 2, deletedAt = 9))
        assertTrue(manager.queueRestore(e1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 2)))
        server.on("POST", "${putPath("expenses", e1)}/restore") { Reply(200, """{"expense":${expenseJson(expense(e1, version = 3))}}""") }
        server.on("PUT", putPath("expenses", e1)) { Reply(200, """{"expense":${expenseJson(expense(e1, amount = "120.00", version = 4))}}""") }

        manager.sync()

        assertEquals(listOf("POST", "PUT"), server.writes.map { it.method })
        assertTrue(server.writes[1].body!!.contains("\"baseVersion\":3"))
        assertEquals(4, store.expenses.getValue(e1).version)
        assertNull(store.expenses.getValue(e1).deletedAt)
        assertTrue(store.outboxItems.isEmpty())
    }

    @Test
    fun `recurring expenses are queued and sent like expenses`() = runBlocking {
        seedGroup()
        manager.queueSave(SharedEntity.Recurring(recurring(r1, version = 0, rev = 0)))
        server.on("PUT", putPath("recurring", r1)) { Reply(201, """{"recurring":${recurringJson(recurring(r1))}}""") }

        manager.sync()

        val body = server.writes.single().body!!
        assertTrue(body.contains("\"interval\":\"MONTHLY\""))
        assertTrue(body.contains("\"nextDate\":\"2026-12-01\""))
        assertTrue(body.contains("\"anchorDay\":1"))
        assertFalse(store.recurrings.getValue(r1).pending)
    }

    // ── Pull ──

    @Test
    fun `pulls page by page and moves the cursor to the answer's rev`() = runBlocking {
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(9)) }
        server.on("GET", "/v1/groups/$G/changes") { seen ->
            when (seen.query) {
                // The group's own rev (9) is ahead of this partial page's rev (4).
                "since=0" -> Reply(200, changes(4, hasMore = true, group = group(9), members = listOf(member(A, "Member A"), member(B, "Member B")), expenses = listOf(expense(e1, rev = 3)), activity = listOf(activity(4, B))))
                "since=4" -> Reply(200, changes(9, expenses = listOf(expense(e2, rev = 9)), activity = listOf(activity(9, B))))
                else -> Reply(422, error("invalid", field = "since"))
            }
        }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.DONE, result.status)
        assertEquals(listOf("since=0", "since=4"), server.seen.filter { it.path.endsWith("/changes") }.map { it.query })
        assertEquals(9L, store.cursors[G])
        assertEquals(A, store.groups.getValue(G).myMemberId)
        assertEquals(setOf(e1, e2), store.expenses.keys)
        assertEquals(2, store.members.size)
        assertEquals(2, store.activity.size)
    }

    @Test
    fun `does not ask for changes when the group's rev has not moved`() = runBlocking {
        seedGroup(rev = 5)
        manager.sync()
        assertTrue(server.seen.none { it.path.endsWith("/changes") })
    }

    @Test
    fun `a server row does not overwrite a row with an unsent change`() = runBlocking {
        seedGroup(rev = 5)
        store.upsertExpense(expense(e1, version = 1))
        manager.queueSave(SharedEntity.Expense(expense(e1, amount = "120.00", version = 1)))
        server.on("PUT", putPath("expenses", e1)) { Reply(422, error("invalid", field = "shares")) }
        val newer = expense(e1, amount = "300.00", version = 2, rev = 6)
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(6)) }
        server.on("GET", "/v1/groups/$G/changes") { Reply(200, changes(6, expenses = listOf(newer))) }

        manager.sync()

        assertEquals(d("120.00"), store.expenses.getValue(e1).amount)
        assertTrue(store.expenses.getValue(e1).pending)
        assertEquals(6L, store.cursors[G])
        // The newest server copy is kept with the change, so discarding it shows that copy.
        manager.discard(item(e1).id)
        assertEquals(newer, store.expenses.getValue(e1))
    }

    @Test
    fun `removes a group that answers 403 with all its rows`() = runBlocking {
        seedGroup(rev = 5)
        store.upsertExpense(expense(e1))
        store.upsertSettlement(settlement(s1))
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(6)) }
        server.on("GET", "/v1/groups/$G/changes") { Reply(403, error("forbidden")) }
        // Push first: this edit is refused the same way, then the group goes.
        manager.queueSave(SharedEntity.Expense(expense(e2, version = 0)))
        server.on("PUT", ".*") { Reply(403, error("forbidden")) }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.DONE, result.status)
        assertTrue(store.groups.isEmpty())
        assertTrue(store.members.isEmpty())
        assertTrue(store.expenses.isEmpty())
        assertTrue(store.settlements.isEmpty())
        assertTrue(store.outboxItems.isEmpty())
    }

    @Test
    fun `removes a group that was deleted and reports who deleted it`() = runBlocking {
        seedGroup(rev = 5)
        store.upsertExpense(expense(e1))
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(6)) }
        server.on("GET", "/v1/groups/$G/changes") {
            Reply(200, changes(6, group = group(6, deletedAt = 50), activity = listOf(activity(6, B, type = "GROUP_DELETED", entityId = G))))
        }

        val result = manager.sync()

        assertTrue(store.groups.isEmpty())
        assertTrue(store.expenses.isEmpty())
        assertEquals(listOf(ActivityType.GROUP_DELETED), result.newActivityByOthers.map { it.type })
    }

    @Test
    fun `removes a group missing from my groups`() = runBlocking {
        seedGroup(rev = 5)
        store.upsertExpense(expense(e1))
        server.on("GET", "/v1/me/groups") { Reply(200, """{"groups":[]}""") }

        manager.sync()

        assertTrue(store.groups.isEmpty())
        assertTrue(store.expenses.isEmpty())
    }

    @Test
    fun `skips an activity type it does not know`() = runBlocking {
        seedGroup(rev = 5)
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(7)) }
        server.on("GET", "/v1/groups/$G/changes") {
            Reply(200, changes(7, activity = listOf(activity(6, B, type = "SOMETHING_NEW"), activity(7, B))))
        }

        val result = manager.sync()

        assertEquals(SharedSyncStatus.DONE, result.status)
        assertEquals(listOf(7L), store.activity.values.map { it.seq })
        assertEquals(7L, store.cursors[G])
    }

    @Test
    fun `reports new activity by other members only, and none on a group's first download`() = runBlocking {
        var rev = 2L
        server.on("GET", "/v1/me/groups") { Reply(200, myGroups(rev)) }
        server.on("GET", "/v1/groups/$G/changes") { seen ->
            if (seen.query == "since=0") {
                Reply(200, changes(2, group = group(2), activity = listOf(activity(1, A, "GROUP_CREATED", G), activity(2, B, "MEMBER_JOINED", B))))
            } else {
                Reply(200, changes(5, activity = listOf(activity(3, A), activity(4, B), activity(5, null))))
            }
        }

        assertTrue(manager.sync().newActivityByOthers.isEmpty())

        rev = 5
        val result = manager.sync()

        assertEquals(listOf(4L, 5L), result.newActivityByOthers.map { it.seq })
    }

    @Test
    fun `does nothing without a registration`() = runBlocking {
        session.credentials = null
        assertEquals(SharedSyncStatus.NOT_IN_USE, manager.sync().status)
        assertTrue(server.seen.isEmpty())
    }
}
