package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.data.sharedexpenses.ScriptedServer.Reply
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Wire mapping, with JSON samples from docs/shared-expenses-api.md ("Objects"). */
class SharedWireTest {
    private val g = "11111111-1111-4111-8111-111111111111"
    private val m1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val m2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val x = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private val groupSample = """{ "id": "$g", "name": "Flat 4B", "defaultCurrency": "INR", "simplifyDebts": true,
        "createdAt": 0, "updatedAt": 0, "deletedAt": null, "rev": 12 }"""

    private val memberSample = """{ "id": "$m2", "groupId": "$g", "displayName": "Roommate", "joined": false,
        "removedAt": null, "rev": 3 }"""

    private val expenseSample = """{ "id": "$x", "groupId": "$g", "description": "Groceries",
        "amount": "1240.00", "currency": "INR", "date": "2026-11-10",
        "category": "Groceries", "notes": null,
        "splitMethod": "EQUAL",
        "payers": [ { "memberId": "$m1", "amount": "1240.00" } ],
        "shares": [ { "memberId": "$m1", "amount": "620.00", "weight": null },
                    { "memberId": "$m2", "amount": "620.00", "weight": null } ],
        "recurringId": null,
        "createdBy": "$m1", "updatedBy": "$m1",
        "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 7 }"""

    private val settlementSample = """{ "id": "$x", "groupId": "$g", "fromMemberId": "$m2", "toMemberId": "$m1",
        "amount": "500.00", "currency": "INR", "date": "2026-12-05", "notes": null,
        "createdBy": "$m2", "updatedBy": "$m2",
        "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 9 }"""

    private val recurringSample = """{ "id": "$x", "groupId": "$g",
        "template": { "description": "Rent", "amount": "30000.00", "currency": "INR",
                      "category": "Rent", "notes": null, "splitMethod": "SHARES",
                      "payers": [ { "memberId": "$m1", "amount": "30000.00" } ],
                      "shares": [ { "memberId": "$m1", "amount": "20000.00", "weight": "2" },
                                  { "memberId": "$m2", "amount": "10000.00", "weight": "1" } ] },
        "interval": "MONTHLY", "anchorDay": 1, "nextDate": "2026-12-01",
        "createdBy": "$m1", "updatedBy": "$m1",
        "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 10 }"""

    private fun activitySample(type: String, summary: String) = """{ "seq": 7, "groupId": "$g", "actorMemberId": "$m1",
        "type": "$type", "entityId": "$x", "summary": $summary, "createdAt": 0 }"""

    @Test
    fun `group and member map to the domain`() {
        val group = SharedJson.decodeFromString(GroupWire.serializer(), groupSample).toDomain(myMemberId = m1)
        assertEquals("Flat 4B", group.name)
        assertEquals("INR", group.defaultCurrency)
        assertTrue(group.simplifyDebts)
        assertEquals(12L, group.rev)
        assertEquals(m1, group.myMemberId)

        val member = SharedJson.decodeFromString(MemberWire.serializer(), memberSample).toDomain()
        assertEquals("Roommate", member.displayName)
        assertFalse(member.joined)
        assertNull(member.removedAt)
    }

    @Test
    fun `an expense keeps decimal amounts with its currency`() {
        val e = SharedJson.decodeFromString(ExpenseWire.serializer(), expenseSample).toDomain()!!
        assertEquals(BigDecimal("1240.00"), e.amount)
        assertEquals("INR", e.currency)
        assertEquals(LocalDate.of(2026, 11, 10), e.date)
        assertEquals(SplitMethod.EQUAL, e.splitMethod)
        assertEquals(listOf(PayerPart(m1, BigDecimal("1240.00"))), e.payers)
        assertEquals(listOf(SharePart(m1, BigDecimal("620.00")), SharePart(m2, BigDecimal("620.00"))), e.shares)
        assertEquals(1, e.version)
        assertFalse(e.pending)
        // And back: the same JSON object.
        val again = Json.parseToJsonElement(SharedJson.encodeToString(ExpenseWire.serializer(), e.toWire())).jsonObject
        assertEquals("1240.00", again.getValue("amount").jsonPrimitive.content)
        assertEquals("620.00", again.getValue("shares").jsonArray[1].jsonObject.getValue("amount").jsonPrimitive.content)
    }

    @Test
    fun `settlement and recurring map to the domain`() {
        val s = SharedJson.decodeFromString(SettlementWire.serializer(), settlementSample).toDomain()!!
        assertEquals(m2, s.fromMemberId)
        assertEquals(m1, s.toMemberId)
        assertEquals(BigDecimal("500.00"), s.amount)

        val r = SharedJson.decodeFromString(RecurringWire.serializer(), recurringSample).toDomain()!!
        assertEquals(RecurringInterval.MONTHLY, r.interval)
        assertEquals(1, r.anchorDay)
        assertEquals(LocalDate.of(2026, 12, 1), r.nextDate)
        assertEquals(SplitMethod.SHARES, r.template.splitMethod)
        assertEquals(listOf("2", "1"), r.template.shares.map { it.weight })
    }

    @Test
    fun `activity summaries become text and unknown types are skipped`() {
        val added = SharedJson.decodeFromString(
            ActivityWire.serializer(),
            activitySample("EXPENSE_ADDED", """{ "description": "Groceries", "amount": "1240.00", "currency": "INR" }"""),
        ).toDomain()!!
        assertEquals(ActivityType.EXPENSE_ADDED, added.type)
        assertEquals(mapOf("description" to "Groceries", "amount" to "1240.00", "currency" to "INR"), added.summary)

        val settings = SharedJson.decodeFromString(ActivityWire.serializer(), activitySample("GROUP_SETTINGS_CHANGED", """{ "simplifyDebts": false }""")).toDomain()!!
        assertEquals(mapOf("simplifyDebts" to "false"), settings.summary)

        assertNull(SharedJson.decodeFromString(ActivityWire.serializer(), activitySample("PUSH_SENT", "{}")).toDomain())
    }

    @Test
    fun `a changes page keeps known rows and drops unknown activity`() {
        val page = """{ "rev": 12, "hasMore": false, "me": "$m1", "group": $groupSample,
            "members": [ $memberSample ], "expenses": [ $expenseSample ], "settlements": [ $settlementSample ],
            "recurring": [ $recurringSample ],
            "activity": [ ${activitySample("EXPENSE_ADDED", "{}")}, ${activitySample("SOMETHING_LATER", "{}")} ], "extra": 1 }"""
        val changes = SharedJson.decodeFromString(ChangesWire.serializer(), page).toDomain()
        assertEquals(12L, changes.rev)
        assertEquals(m1, changes.me)
        assertEquals(m1, changes.group!!.myMemberId)
        assertEquals(1, changes.expenses.size)
        assertEquals(1, changes.settlements.size)
        assertEquals(1, changes.recurring.size)
        assertEquals(1, changes.activity.size)

        val partial = SharedJson.decodeFromString(ChangesWire.serializer(), """{ "rev": 3, "hasMore": true, "me": "$m1" }""").toDomain()
        assertNull(partial.group)
        assertTrue(partial.expenses.isEmpty())
    }

    @Test
    fun `amounts are sent with the currency's decimals`() {
        assertEquals("1240.00", wireAmount(BigDecimal("1240"), "INR"))
        assertEquals("1240.50", wireAmount(BigDecimal("1240.5"), "INR"))
        assertEquals("1240", wireAmount(BigDecimal("1240.00"), "JPY"))
        assertEquals("1.500", wireAmount(BigDecimal("1.5"), "KWD"))
    }

    @Test
    fun `an expense body sends amounts as strings, trims text and leaves out empty fields`() {
        val e = SharedJson.decodeFromString(ExpenseWire.serializer(), expenseSample).toDomain()!!
            .copy(description = "  Groceries ", category = " ", notes = "")
        val body = Json.parseToJsonElement(SharedJson.encodeToString(ExpenseBody.serializer(), e.toBody(baseVersion = 0))).jsonObject
        assertEquals("0", body.getValue("baseVersion").jsonPrimitive.content)
        assertEquals("Groceries", body.getValue("description").jsonPrimitive.content)
        assertTrue(body.getValue("amount").jsonPrimitive.isString)
        assertFalse("category" in body)
        assertFalse("notes" in body)
        assertFalse("recurringId" in body)
        // EQUAL shares carry no weight.
        assertTrue(body.getValue("shares").jsonArray.all { "weight" !in (it as JsonObject) })
    }

    // ── Errors through the client ──

    private fun client(status: Int, body: String) = SharedApiClient(ScriptedServer().apply { on("PUT", ".*") { Reply(status, body) } }.engine)

    private val expense = SharedJson.decodeFromString(ExpenseWire.serializer(), expenseSample).toDomain()!!

    @Test
    fun `error bodies map to typed errors with code, message and field`() = runBlocking {
        val r = client(422, """{ "error": "invalid", "message": "Shares must add up to the amount", "field": "shares" }""")
            .putExpense(FakeSession.CREDENTIALS, expense, 0)
        assertEquals(RemoteResult.Err(SharedError.Server(422, SharedServerCode.INVALID, "Shares must add up to the amount", "shares")), r)

        val busy = client(503, """{ "error": "busy", "message": "Try again" }""").putExpense(FakeSession.CREDENTIALS, expense, 0)
        assertEquals(SharedServerCode.BUSY, ((busy as RemoteResult.Err).error as SharedError.Server).code)
        assertTrue(busy.error.retryable)

        val html = client(502, "<html>Bad gateway</html>").putExpense(FakeSession.CREDENTIALS, expense, 0)
        assertEquals(SharedServerCode.INTERNAL, ((html as RemoteResult.Err).error as SharedError.Server).code)
    }

    @Test
    fun `a 409 conflict carries the server's current row`() = runBlocking {
        val r = client(409, """{ "error": "conflict", "message": "Someone changed this first", "current": $expenseSample }""")
            .putExpense(FakeSession.CREDENTIALS, expense, 1)
        assertEquals(RemoteResult.Conflict(expense), r)

        val already = client(409, """{ "error": "already_member", "message": "You already hold another seat" }""")
            .putExpense(FakeSession.CREDENTIALS, expense, 1)
        assertEquals(SharedServerCode.ALREADY_MEMBER, ((already as RemoteResult.Err).error as SharedError.Server).code)
    }

    @Test
    fun `an unreadable success answer is a network error, not a crash`() = runBlocking {
        val r = client(200, "{ not json").putExpense(FakeSession.CREDENTIALS, expense, 0)
        assertTrue((r as RemoteResult.Err).error is SharedError.Network)
    }
}
