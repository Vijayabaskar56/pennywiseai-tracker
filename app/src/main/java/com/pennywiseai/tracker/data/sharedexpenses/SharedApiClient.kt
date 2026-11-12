package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement

/** This phone's identity on one server. [toString] hides the secret. */
class SharedCredentials(val baseUrl: String, val userId: String, val secret: String) {
    internal val header: String get() = "Device $userId.$secret"

    override fun toString() = "SharedCredentials($baseUrl, $userId)"
}

/** One answer from the server, already mapped to domain models. */
sealed interface RemoteResult<out T> {
    data class Ok<out T>(val value: T) : RemoteResult<T>

    /** `409 conflict`: someone changed the row first. [current] is the server's copy. */
    data class Conflict<out T>(val current: T) : RemoteResult<T>

    data class Err(val error: SharedError) : RemoteResult<Nothing>
}

/** A group in `GET /v1/me/groups`: its revision and this phone's seat. */
data class RemoteGroupRef(val id: String, val rev: Long, val memberId: String)

/** A newly created group with the creator's seat. */
data class RemoteCreatedGroup(val group: SharedGroup, val members: List<SharedMember>)

/** One page of `GET /v1/groups/:g/changes`. Activity of unknown types and unreadable rows are already left out. */
data class RemoteChanges(
    val rev: Long,
    val hasMore: Boolean,
    val me: String,
    val group: SharedGroup?,
    val members: List<SharedMember>,
    val expenses: List<SharedExpense>,
    val settlements: List<SharedSettlement>,
    val recurring: List<SharedRecurring>,
    val activity: List<SharedActivity>,
)

/** Every route of docs/shared-expenses-api.md. The sync manager and repository talk to the server only through this. */
interface SharedRemote {
    suspend fun register(baseUrl: String): RemoteResult<SharedCredentials>
    suspend fun setDisplayName(c: SharedCredentials, displayName: String): RemoteResult<Unit>
    suspend fun myGroups(c: SharedCredentials): RemoteResult<List<RemoteGroupRef>>

    suspend fun createGroup(c: SharedCredentials, name: String, defaultCurrency: String, displayName: String): RemoteResult<RemoteCreatedGroup>
    suspend fun updateGroup(
        c: SharedCredentials,
        groupId: String,
        name: String? = null,
        simplifyDebts: Boolean? = null,
        defaultCurrency: String? = null,
    ): RemoteResult<SharedGroup>
    suspend fun deleteGroup(c: SharedCredentials, groupId: String): RemoteResult<Unit>
    suspend fun addMember(c: SharedCredentials, groupId: String, displayName: String): RemoteResult<SharedMember>
    suspend fun renameMember(c: SharedCredentials, groupId: String, memberId: String, displayName: String): RemoteResult<SharedMember>
    suspend fun removeMember(c: SharedCredentials, groupId: String, memberId: String): RemoteResult<Unit>

    suspend fun createInvite(c: SharedCredentials, groupId: String, memberId: String?): RemoteResult<SharedInvite>
    suspend fun listInvites(c: SharedCredentials, groupId: String): RemoteResult<List<SharedInviteInfo>>
    suspend fun revokeInvite(c: SharedCredentials, groupId: String, inviteId: String): RemoteResult<Unit>
    suspend fun previewInvite(c: SharedCredentials, token: String): RemoteResult<SharedInvitePreview>
    suspend fun acceptInvite(c: SharedCredentials, token: String, displayName: String): RemoteResult<SharedJoin>

    suspend fun putExpense(c: SharedCredentials, expense: SharedExpense, baseVersion: Int): RemoteResult<SharedExpense>
    suspend fun deleteExpense(c: SharedCredentials, groupId: String, id: String): RemoteResult<Unit>
    suspend fun restoreExpense(c: SharedCredentials, groupId: String, id: String): RemoteResult<SharedExpense>
    suspend fun putSettlement(c: SharedCredentials, settlement: SharedSettlement, baseVersion: Int): RemoteResult<SharedSettlement>
    suspend fun deleteSettlement(c: SharedCredentials, groupId: String, id: String): RemoteResult<Unit>
    suspend fun putRecurring(c: SharedCredentials, recurring: SharedRecurring, baseVersion: Int): RemoteResult<SharedRecurring>
    suspend fun deleteRecurring(c: SharedCredentials, groupId: String, id: String): RemoteResult<Unit>

    suspend fun changes(c: SharedCredentials, groupId: String, since: Long): RemoteResult<RemoteChanges>
}

/** [SharedRemote] over Ktor. Tests pass a MockEngine; the app passes OkHttp (platform TLS). */
class SharedApiClient(engine: HttpClientEngine) : SharedRemote {

    private val http = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private class Raw(val status: Int, val text: String)

    private suspend fun send(method: HttpMethod, url: String, auth: SharedCredentials?, body: String?): Raw {
        val response = http.request(url) {
            this.method = method
            auth?.let { header(HttpHeaders.Authorization, it.header) }
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        return Raw(response.status.value, response.bodyAsText())
    }

    /**
     * Sends one request. A 2xx answer is decoded with [ok]; `409 conflict` with
     * [current]; anything else becomes [SharedError.Server]. Exceptions from the
     * network and unreadable answers become [SharedError.Network].
     */
    private suspend fun <T> call(
        method: HttpMethod,
        url: String,
        auth: SharedCredentials?,
        body: String? = null,
        current: ((JsonElement) -> T?)? = null,
        ok: (String) -> T,
    ): RemoteResult<T> {
        val raw = try {
            send(method, url, auth, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RemoteResult.Err(SharedError.Network(e.message ?: e::class.simpleName))
        }
        if (raw.status in 200..299) {
            return try {
                RemoteResult.Ok(ok(raw.text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RemoteResult.Err(SharedError.Network("Unreadable answer from the server"))
            }
        }
        val error = runCatching { SharedJson.decodeFromString(ErrorWire.serializer(), raw.text) }.getOrNull()
        val code = SharedServerCode.fromWire(error?.error, raw.status)
        if (raw.status == 409 && code == SharedServerCode.CONFLICT && current != null && error?.current != null) {
            runCatching { current(error.current) }.getOrNull()?.let { return RemoteResult.Conflict(it) }
        }
        return RemoteResult.Err(SharedError.Server(raw.status, code, error?.message ?: "HTTP ${raw.status}", error?.field))
    }

    private fun <T> decode(strategy: DeserializationStrategy<T>): (String) -> T = { SharedJson.decodeFromString(strategy, it) }

    private fun <T> encode(strategy: SerializationStrategy<T>, value: T): String = SharedJson.encodeToString(strategy, value)

    private fun <T> element(strategy: DeserializationStrategy<T>): (JsonElement) -> T = { SharedJson.decodeFromJsonElement(strategy, it) }

    private val unit: (String) -> Unit = {}

    private fun SharedCredentials.url(vararg segments: String) =
        baseUrl + "/v1/" + segments.joinToString("/") { it.encodeURLPathPart() }

    override suspend fun register(baseUrl: String): RemoteResult<SharedCredentials> =
        call(HttpMethod.Post, "$baseUrl/v1/device", auth = null) { text ->
            val d = SharedJson.decodeFromString(DeviceWire.serializer(), text)
            SharedCredentials(baseUrl, d.userId, d.secret)
        }

    override suspend fun setDisplayName(c: SharedCredentials, displayName: String) =
        call(HttpMethod.Put, c.url("me"), c, encode(DisplayNameBody.serializer(), DisplayNameBody(displayName)), ok = unit)

    override suspend fun myGroups(c: SharedCredentials) = call(HttpMethod.Get, c.url("me", "groups"), c) { text ->
        SharedJson.decodeFromString(MyGroupsWire.serializer(), text).groups.map { RemoteGroupRef(it.id, it.rev, it.memberId) }
    }

    override suspend fun createGroup(c: SharedCredentials, name: String, defaultCurrency: String, displayName: String) =
        call(HttpMethod.Post, c.url("groups"), c, encode(CreateGroupBody.serializer(), CreateGroupBody(name, defaultCurrency, displayName))) { text ->
            val w = SharedJson.decodeFromString(CreateGroupWire.serializer(), text)
            RemoteCreatedGroup(w.group.toDomain(myMemberId = w.me), w.members.map { it.toDomain() })
        }

    override suspend fun updateGroup(c: SharedCredentials, groupId: String, name: String?, simplifyDebts: Boolean?, defaultCurrency: String?) =
        call(HttpMethod.Patch, c.url("groups", groupId), c, encode(PatchGroupBody.serializer(), PatchGroupBody(name, simplifyDebts, defaultCurrency))) { text ->
            SharedJson.decodeFromString(GroupResponse.serializer(), text).group.toDomain(myMemberId = null)
        }

    override suspend fun deleteGroup(c: SharedCredentials, groupId: String) = call(HttpMethod.Delete, c.url("groups", groupId), c, ok = unit)

    override suspend fun addMember(c: SharedCredentials, groupId: String, displayName: String) =
        call(HttpMethod.Post, c.url("groups", groupId, "members"), c, encode(DisplayNameBody.serializer(), DisplayNameBody(displayName))) {
            SharedJson.decodeFromString(MemberResponse.serializer(), it).member.toDomain()
        }

    override suspend fun renameMember(c: SharedCredentials, groupId: String, memberId: String, displayName: String) =
        call(HttpMethod.Patch, c.url("groups", groupId, "members", memberId), c, encode(DisplayNameBody.serializer(), DisplayNameBody(displayName))) {
            SharedJson.decodeFromString(MemberResponse.serializer(), it).member.toDomain()
        }

    override suspend fun removeMember(c: SharedCredentials, groupId: String, memberId: String) =
        call(HttpMethod.Delete, c.url("groups", groupId, "members", memberId), c, ok = unit)

    override suspend fun createInvite(c: SharedCredentials, groupId: String, memberId: String?) =
        call(HttpMethod.Post, c.url("groups", groupId, "invites"), c, encode(InviteBody.serializer(), InviteBody(memberId))) {
            val w = SharedJson.decodeFromString(InviteWire.serializer(), it)
            SharedInvite(w.id, w.token, w.url, w.memberId, w.expiresAt)
        }

    override suspend fun listInvites(c: SharedCredentials, groupId: String) = call(HttpMethod.Get, c.url("groups", groupId, "invites"), c) { text ->
        SharedJson.decodeFromString(InvitesWire.serializer(), text).invites.map { SharedInviteInfo(it.id, it.memberId, it.expiresAt, it.uses) }
    }

    override suspend fun revokeInvite(c: SharedCredentials, groupId: String, inviteId: String) =
        call(HttpMethod.Delete, c.url("groups", groupId, "invites", inviteId), c, ok = unit)

    override suspend fun previewInvite(c: SharedCredentials, token: String) = call(HttpMethod.Get, c.url("invites", token), c) {
        val w = SharedJson.decodeFromString(InvitePreviewWire.serializer(), it)
        SharedInvitePreview(w.groupId, w.groupName, w.memberDisplayName, w.expiresAt, w.alreadyMember)
    }

    override suspend fun acceptInvite(c: SharedCredentials, token: String, displayName: String) =
        call(HttpMethod.Post, c.url("invites", token, "accept"), c, encode(DisplayNameBody.serializer(), DisplayNameBody(displayName))) {
            val w = SharedJson.decodeFromString(AcceptWire.serializer(), it)
            SharedJoin(w.groupId, w.memberId)
        }

    override suspend fun putExpense(c: SharedCredentials, expense: SharedExpense, baseVersion: Int) = call(
        HttpMethod.Put, c.url("groups", expense.groupId, "expenses", expense.id), c,
        encode(ExpenseBody.serializer(), expense.toBody(baseVersion)),
        current = { element(ExpenseWire.serializer())(it).toDomain() },
    ) { readable(decode(ExpenseResponse.serializer())(it).expense.toDomain()) }

    override suspend fun deleteExpense(c: SharedCredentials, groupId: String, id: String) =
        call(HttpMethod.Delete, c.url("groups", groupId, "expenses", id), c, ok = unit)

    override suspend fun restoreExpense(c: SharedCredentials, groupId: String, id: String) =
        call(HttpMethod.Post, c.url("groups", groupId, "expenses", id, "restore"), c) {
            readable(decode(ExpenseResponse.serializer())(it).expense.toDomain())
        }

    override suspend fun putSettlement(c: SharedCredentials, settlement: SharedSettlement, baseVersion: Int) = call(
        HttpMethod.Put, c.url("groups", settlement.groupId, "settlements", settlement.id), c,
        encode(SettlementBody.serializer(), settlement.toBody(baseVersion)),
        current = { element(SettlementWire.serializer())(it).toDomain() },
    ) { readable(decode(SettlementResponse.serializer())(it).settlement.toDomain()) }

    override suspend fun deleteSettlement(c: SharedCredentials, groupId: String, id: String) =
        call(HttpMethod.Delete, c.url("groups", groupId, "settlements", id), c, ok = unit)

    override suspend fun putRecurring(c: SharedCredentials, recurring: SharedRecurring, baseVersion: Int) = call(
        HttpMethod.Put, c.url("groups", recurring.groupId, "recurring", recurring.id), c,
        encode(RecurringBody.serializer(), recurring.toBody(baseVersion)),
        current = { element(RecurringWire.serializer())(it).toDomain() },
    ) { readable(decode(RecurringResponse.serializer())(it).recurring.toDomain()) }

    override suspend fun deleteRecurring(c: SharedCredentials, groupId: String, id: String) =
        call(HttpMethod.Delete, c.url("groups", groupId, "recurring", id), c, ok = unit)

    override suspend fun changes(c: SharedCredentials, groupId: String, since: Long) =
        call(HttpMethod.Get, c.url("groups", groupId, "changes") + "?since=$since", c) { text ->
            SharedJson.decodeFromString(ChangesWire.serializer(), text).toDomain()
        }

    private fun <T> readable(value: T?): T = value ?: error("Unreadable row")
}

/** A `changes` page as domain models. Rows this app cannot read and unknown activity types are skipped. */
internal fun ChangesWire.toDomain() = RemoteChanges(
    rev = rev,
    hasMore = hasMore,
    me = me,
    group = group?.toDomain(myMemberId = me),
    members = members.map { it.toDomain() },
    expenses = expenses.mapNotNull { it.toDomain() },
    settlements = settlements.mapNotNull { it.toDomain() },
    recurring = recurring.mapNotNull { it.toDomain() },
    activity = activity.mapNotNull { it.toDomain() },
)
