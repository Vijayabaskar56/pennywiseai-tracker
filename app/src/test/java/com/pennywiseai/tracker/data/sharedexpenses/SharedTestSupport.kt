package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseTemplate
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate

/** [SharedLocalStore] in memory. Single-threaded tests, so a transaction just runs its block. */
class FakeSharedLocalStore : SharedLocalStore {
    val groups = linkedMapOf<String, SharedGroup>()
    val cursors = mutableMapOf<String, Long>()
    val members = linkedMapOf<String, SharedMember>()
    val expenses = linkedMapOf<String, SharedExpense>()
    val settlements = linkedMapOf<String, SharedSettlement>()
    val recurrings = linkedMapOf<String, SharedRecurring>()
    val activity = linkedMapOf<Pair<String, Long>, SharedActivity>()
    val outboxItems = sortedMapOf<Long, SharedOutboxItem>()
    private var nextId = 1L

    override suspend fun <R> transaction(block: suspend () -> R): R = block()
    override suspend fun groupIds() = groups.keys.toList()
    override suspend fun group(id: String) = groups[id]
    override suspend fun groupCursor(id: String) = if (id in groups) cursors[id] ?: 0L else null
    override suspend fun upsertGroup(group: SharedGroup) {
        groups[group.id] = group.copy(myMemberId = group.myMemberId ?: groups[group.id]?.myMemberId)
    }
    override suspend fun setGroupCursor(id: String, rev: Long, myMemberId: String) {
        val g = groups[id] ?: return
        cursors[id] = rev
        groups[id] = g.copy(myMemberId = myMemberId)
    }
    override suspend fun removeGroup(id: String) {
        groups.remove(id)
        cursors.remove(id)
        members.values.removeAll { it.groupId == id }
        expenses.values.removeAll { it.groupId == id }
        settlements.values.removeAll { it.groupId == id }
        recurrings.values.removeAll { it.groupId == id }
        activity.values.removeAll { it.groupId == id }
        outboxItems.values.removeAll { it.groupId == id }
    }
    override suspend fun clearAll() {
        groups.clear(); cursors.clear(); members.clear(); expenses.clear(); settlements.clear()
        recurrings.clear(); activity.clear(); outboxItems.clear()
    }
    override suspend fun members(groupId: String) = members.values.filter { it.groupId == groupId }
    override suspend fun upsertMember(member: SharedMember) { members[member.id] = member }
    override suspend fun expense(id: String) = expenses[id]
    override suspend fun upsertExpense(expense: SharedExpense) { expenses[expense.id] = expense }
    override suspend fun deleteExpense(id: String) { expenses.remove(id) }
    override suspend fun settlement(id: String) = settlements[id]
    override suspend fun upsertSettlement(settlement: SharedSettlement) { settlements[settlement.id] = settlement }
    override suspend fun deleteSettlement(id: String) { settlements.remove(id) }
    override suspend fun recurring(id: String) = recurrings[id]
    override suspend fun upsertRecurring(recurring: SharedRecurring) { recurrings[recurring.id] = recurring }
    override suspend fun deleteRecurring(id: String) { recurrings.remove(id) }
    override suspend fun insertActivity(activity: SharedActivity) { this.activity.putIfAbsent(activity.groupId to activity.seq, activity) }
    override suspend fun outbox() = outboxItems.values.toList()
    override suspend fun outboxItem(id: Long) = outboxItems[id]
    override suspend fun outboxFor(type: SharedEntityType, entityId: String) =
        outboxItems.values.firstOrNull { it.entityType == type && it.entityId == entityId }
    override suspend fun insertOutbox(item: SharedOutboxItem): Long {
        check(outboxFor(item.entityType, item.entityId) == null) { "one outbox item per row" }
        val id = nextId++
        outboxItems[id] = item.copy(id = id)
        return id
    }
    override suspend fun updateOutbox(item: SharedOutboxItem) { outboxItems[item.id] = item }
    override suspend fun deleteOutbox(id: Long) { outboxItems.remove(id) }
}

class FakeSession(override var credentials: SharedCredentials? = CREDENTIALS) : SharedSession {
    override var lastSyncAt: Long? = null

    companion object {
        val CREDENTIALS = SharedCredentials("https://shared.test", "0123456789abcdef0123456789abcdef", "s".repeat(43))
    }
}

/** A request the fake server saw. */
data class Seen(val method: String, val path: String, val query: String, val body: String?, val auth: String?)

/** A scripted server for MockEngine: routes match method and path; the newest matching route answers. */
class ScriptedServer {
    class Reply(val status: Int, val body: String = "")

    private class Route(val method: String, val path: Regex, val reply: suspend (Seen) -> Reply)

    private val routes = mutableListOf<Route>()
    val seen = mutableListOf<Seen>()

    fun on(method: String, path: String, reply: suspend (Seen) -> Reply) {
        routes += Route(method, Regex(path), reply)
    }

    /** Requests other than the sync's reads of `me/groups` and `changes`. */
    val writes: List<Seen> get() = seen.filter { !(it.method == "GET" && (it.path.endsWith("/me/groups") || it.path.endsWith("/changes"))) }

    val engine = MockEngine { request ->
        val body = when (val content = request.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> null
        }
        val s = Seen(request.method.value, request.url.encodedPath, request.url.encodedQuery, body, request.headers[HttpHeaders.Authorization])
        seen += s
        val route = routes.lastOrNull { it.method == s.method && it.path.matches(s.path) }
        val reply = route?.reply?.invoke(s) ?: Reply(404, """{"error":"not_found","message":"No such route"}""")
        if (reply.status == NETWORK_DOWN) throw IOException("Network is down")
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf(HttpHeaders.ContentType, "application/json"))
    }

    companion object {
        /** A [Reply] status that makes the engine throw, as a dropped connection does. */
        const val NETWORK_DOWN = -1
    }
}

/** Builders for sync tests. IDs are generic UUID-shaped placeholders, names are "Member A" style. */
object SharedSamples {
    const val G = "11111111-1111-4111-8111-111111111111"
    const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    fun group(rev: Long = 1, deletedAt: Long? = null, name: String = "Flat") =
        SharedGroup(G, name, "INR", true, 1, 1, deletedAt, rev, myMemberId = null)

    fun member(id: String, name: String, rev: Long = 1) = SharedMember(id, G, name, joined = true, removedAt = null, rev = rev)

    fun expense(
        id: String,
        amount: String = "100.00",
        description: String = "Groceries",
        version: Int = 1,
        rev: Long = 2,
        deletedAt: Long? = null,
        pending: Boolean = false,
    ): SharedExpense {
        val half = BigDecimal(amount).divide(BigDecimal(2))
        return SharedExpense(
            id = id, groupId = G, description = description, amount = BigDecimal(amount), currency = "INR",
            date = LocalDate.of(2026, 11, 10), category = "Groceries", splitMethod = SplitMethod.EXACT,
            payers = listOf(PayerPart(A, BigDecimal(amount))), shares = listOf(SharePart(A, half), SharePart(B, half)),
            createdBy = A, updatedBy = A, createdAt = 1, updatedAt = 1, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
        )
    }

    fun settlement(id: String, amount: String = "50.00", version: Int = 1, rev: Long = 3) = SharedSettlement(
        id = id, groupId = G, fromMemberId = B, toMemberId = A, amount = BigDecimal(amount), currency = "INR",
        date = LocalDate.of(2026, 12, 5), createdBy = B, updatedBy = B, createdAt = 1, updatedAt = 1, version = version, rev = rev,
    )

    fun recurring(id: String, amount: String = "300.00", version: Int = 1, rev: Long = 4) = SharedRecurring(
        id = id, groupId = G,
        template = ExpenseTemplate(
            description = "Rent", amount = BigDecimal(amount), currency = "INR", splitMethod = SplitMethod.EXACT,
            payers = listOf(PayerPart(A, BigDecimal(amount))),
            shares = listOf(SharePart(A, BigDecimal(amount).divide(BigDecimal(2))), SharePart(B, BigDecimal(amount).divide(BigDecimal(2)))),
        ),
        interval = RecurringInterval.MONTHLY, anchorDay = 1, nextDate = LocalDate.of(2026, 12, 1),
        createdBy = A, updatedBy = A, createdAt = 1, updatedAt = 1, version = version, rev = rev,
    )

    fun activity(seq: Long, actor: String?, type: String = "EXPENSE_ADDED", entityId: String = id(seq.toInt())) =
        """{"seq":$seq,"groupId":"$G","actorMemberId":${actor?.let { "\"$it\"" } ?: "null"},"type":"$type","entityId":"$entityId",""" +
            """"summary":{"description":"Groceries","amount":"100.00","currency":"INR"},"createdAt":$seq}"""

    fun expenseJson(e: SharedExpense) = SharedJson.encodeToString(ExpenseWire.serializer(), e.toWire())
    fun settlementJson(s: SharedSettlement) = SharedJson.encodeToString(SettlementWire.serializer(), s.toWire())
    fun recurringJson(r: SharedRecurring) = SharedJson.encodeToString(RecurringWire.serializer(), r.toWire())
    fun groupJson(g: SharedGroup) = SharedJson.encodeToString(
        GroupWire.serializer(),
        GroupWire(g.id, g.name, g.defaultCurrency, g.simplifyDebts, g.createdAt, g.updatedAt, g.deletedAt, g.rev),
    )
    fun memberJson(m: SharedMember) = SharedJson.encodeToString(MemberWire.serializer(), MemberWire(m.id, m.groupId, m.displayName, m.joined, m.removedAt, m.rev))

    fun myGroups(rev: Long, me: String = A) = """{"groups":[{"id":"$G","rev":$rev,"memberId":"$me"}]}"""

    fun changes(
        rev: Long,
        hasMore: Boolean = false,
        me: String = A,
        group: SharedGroup? = null,
        members: List<SharedMember> = emptyList(),
        expenses: List<SharedExpense> = emptyList(),
        settlements: List<SharedSettlement> = emptyList(),
        recurring: List<SharedRecurring> = emptyList(),
        activity: List<String> = emptyList(),
    ) = buildString {
        append("""{"rev":$rev,"hasMore":$hasMore,"me":"$me",""")
        group?.let { append(""""group":${groupJson(it)},""") }
        append(""""members":[${members.joinToString(",") { memberJson(it) }}],""")
        append(""""expenses":[${expenses.joinToString(",") { expenseJson(it) }}],""")
        append(""""settlements":[${settlements.joinToString(",") { settlementJson(it) }}],""")
        append(""""recurring":[${recurring.joinToString(",") { recurringJson(it) }}],""")
        append(""""activity":[${activity.joinToString(",")}]}""")
    }

    fun error(code: String, message: String = "No", field: String? = null, current: String? = null) = buildString {
        append("""{"error":"$code","message":"$message"""")
        field?.let { append(""","field":"$it"""") }
        current?.let { append(""","current":$it""") }
        append("}")
    }
}
