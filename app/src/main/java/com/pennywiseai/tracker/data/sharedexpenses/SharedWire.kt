package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.loan.SplitRules
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// Wire types for docs/shared-expenses-api.md. Private to the data layer: the
// client maps them to the domain models before anything else sees them.
// Amounts are decimal strings and always travel with their currency.

internal val SharedJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable internal data class DeviceWire(val userId: String, val secret: String)

@Serializable internal data class DisplayNameBody(val displayName: String)

@Serializable internal data class MyGroupsWire(val groups: List<MyGroupWire> = emptyList())

@Serializable internal data class MyGroupWire(val id: String, val rev: Long, val memberId: String)

@Serializable
internal data class GroupWire(
    val id: String,
    val name: String,
    val defaultCurrency: String,
    val simplifyDebts: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val rev: Long = 0,
)

@Serializable
internal data class MemberWire(
    val id: String,
    val groupId: String,
    val displayName: String,
    val joined: Boolean = false,
    val removedAt: Long? = null,
    val rev: Long = 0,
)

@Serializable internal data class PayerWire(val memberId: String, val amount: String)

@Serializable internal data class ShareWire(val memberId: String, val amount: String, val weight: String? = null)

@Serializable
internal data class ExpenseWire(
    val id: String,
    val groupId: String,
    val description: String,
    val amount: String,
    val currency: String,
    val date: String,
    val category: String? = null,
    val notes: String? = null,
    val splitMethod: String,
    val payers: List<PayerWire> = emptyList(),
    val shares: List<ShareWire> = emptyList(),
    val recurringId: String? = null,
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val version: Int = 1,
    val rev: Long = 0,
)

@Serializable
internal data class SettlementWire(
    val id: String,
    val groupId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val amount: String,
    val currency: String,
    val date: String,
    val notes: String? = null,
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val version: Int = 1,
    val rev: Long = 0,
)

@Serializable
internal data class TemplateWire(
    val description: String,
    val amount: String,
    val currency: String,
    val category: String? = null,
    val notes: String? = null,
    val splitMethod: String,
    val payers: List<PayerWire> = emptyList(),
    val shares: List<ShareWire> = emptyList(),
)

@Serializable
internal data class RecurringWire(
    val id: String,
    val groupId: String,
    val template: TemplateWire,
    val interval: String,
    val anchorDay: Int,
    val nextDate: String,
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val version: Int = 1,
    val rev: Long = 0,
)

@Serializable
internal data class ActivityWire(
    val seq: Long,
    val groupId: String,
    val actorMemberId: String? = null,
    val type: String,
    val entityId: String,
    val summary: JsonObject = JsonObject(emptyMap()),
    val createdAt: Long = 0,
)

@Serializable
internal data class ChangesWire(
    val rev: Long,
    val hasMore: Boolean = false,
    val me: String,
    val group: GroupWire? = null,
    val members: List<MemberWire> = emptyList(),
    val expenses: List<ExpenseWire> = emptyList(),
    val settlements: List<SettlementWire> = emptyList(),
    val recurring: List<RecurringWire> = emptyList(),
    val activity: List<ActivityWire> = emptyList(),
)

@Serializable internal data class CreateGroupBody(val name: String, val defaultCurrency: String, val displayName: String)

@Serializable internal data class CreateGroupWire(val group: GroupWire, val me: String, val members: List<MemberWire> = emptyList())

@Serializable
internal data class PatchGroupBody(val name: String? = null, val simplifyDebts: Boolean? = null, val defaultCurrency: String? = null)

@Serializable internal data class GroupResponse(val group: GroupWire)

@Serializable internal data class MemberResponse(val member: MemberWire)

@Serializable internal data class InviteBody(val memberId: String? = null)

@Serializable
internal data class InviteWire(val id: String, val token: String, val url: String, val memberId: String? = null, val expiresAt: Long)

@Serializable internal data class InviteInfoWire(val id: String, val memberId: String? = null, val expiresAt: Long, val uses: Int = 0)

@Serializable internal data class InvitesWire(val invites: List<InviteInfoWire> = emptyList())

@Serializable
internal data class InvitePreviewWire(
    val groupId: String,
    val groupName: String,
    val memberDisplayName: String? = null,
    val expiresAt: Long,
    val alreadyMember: Boolean = false,
)

@Serializable internal data class AcceptWire(val groupId: String, val memberId: String)

@Serializable internal data class ExpenseResponse(val expense: ExpenseWire)

@Serializable internal data class SettlementResponse(val settlement: SettlementWire)

@Serializable internal data class RecurringResponse(val recurring: RecurringWire)

@Serializable
internal data class ExpenseBody(
    val baseVersion: Int,
    val description: String,
    val amount: String,
    val currency: String,
    val date: String,
    val category: String? = null,
    val notes: String? = null,
    val splitMethod: String,
    val payers: List<PayerWire>,
    val shares: List<ShareWire>,
    val recurringId: String? = null,
)

@Serializable
internal data class SettlementBody(
    val baseVersion: Int,
    val fromMemberId: String,
    val toMemberId: String,
    val amount: String,
    val currency: String,
    val date: String,
    val notes: String? = null,
)

@Serializable
internal data class RecurringBody(
    val baseVersion: Int,
    val template: TemplateWire,
    val interval: String,
    val nextDate: String,
    val anchorDay: Int? = null,
)

@Serializable
internal data class ErrorWire(
    val error: String? = null,
    val message: String? = null,
    val field: String? = null,
    val current: JsonElement? = null,
)

// ── Money ──

/** [amount] as the wire wants it: the currency's decimals exactly ("620.00"), never rounded. */
internal fun wireAmount(amount: BigDecimal, currency: String): String {
    val digits = SplitRules.fractionDigits(currency)
    val stripped = amount.stripTrailingZeros()
    return if (stripped.scale() <= digits) stripped.setScale(digits, RoundingMode.UNNECESSARY).toPlainString() else amount.toPlainString()
}

/** Blank → null, as the server stores an empty category or notes. */
internal fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

// ── Wire → domain. A row this app cannot read (an unknown enum) maps to null and is skipped. ──

internal fun GroupWire.toDomain(myMemberId: String?) = SharedGroup(
    id = id, name = name, defaultCurrency = defaultCurrency, simplifyDebts = simplifyDebts,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, rev = rev, myMemberId = myMemberId,
)

internal fun MemberWire.toDomain() = SharedMember(id, groupId, displayName, joined, removedAt, rev)

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

private fun parseAmount(text: String): BigDecimal? = runCatching { BigDecimal(text) }.getOrNull()

private fun List<PayerWire>.toPayers(): List<PayerPart>? = map { PayerPart(it.memberId, parseAmount(it.amount) ?: return null) }

private fun List<ShareWire>.toShares(): List<SharePart>? = map { SharePart(it.memberId, parseAmount(it.amount) ?: return null, it.weight) }

internal fun ExpenseWire.toDomain(pending: Boolean = false): SharedExpense? = SharedExpense(
    id = id, groupId = groupId, description = description,
    amount = parseAmount(amount) ?: return null, currency = currency,
    date = parseDate(date) ?: return null, category = category, notes = notes,
    splitMethod = enumOrNull<SplitMethod>(splitMethod) ?: return null,
    payers = payers.toPayers() ?: return null, shares = shares.toShares() ?: return null,
    recurringId = recurringId, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

internal fun SettlementWire.toDomain(pending: Boolean = false): SharedSettlement? = SharedSettlement(
    id = id, groupId = groupId, fromMemberId = fromMemberId, toMemberId = toMemberId,
    amount = parseAmount(amount) ?: return null, currency = currency, date = parseDate(date) ?: return null,
    notes = notes, createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

internal fun TemplateWire.toDomain(): ExpenseTemplate? = ExpenseTemplate(
    description = description, amount = parseAmount(amount) ?: return null, currency = currency,
    category = category, notes = notes, splitMethod = enumOrNull<SplitMethod>(splitMethod) ?: return null,
    payers = payers.toPayers() ?: return null, shares = shares.toShares() ?: return null,
)

internal fun RecurringWire.toDomain(pending: Boolean = false): SharedRecurring? = SharedRecurring(
    id = id, groupId = groupId, template = template.toDomain() ?: return null,
    interval = enumOrNull<RecurringInterval>(interval) ?: return null, anchorDay = anchorDay,
    nextDate = parseDate(nextDate) ?: return null, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

/** An activity row, or null for a `type` this app does not know (skipped, never a crash). */
internal fun ActivityWire.toDomain(): SharedActivity? = SharedActivity(
    seq = seq, groupId = groupId, actorMemberId = actorMemberId,
    type = enumOrNull<ActivityType>(type) ?: return null, entityId = entityId,
    summary = summary.mapNotNull { (key, value) ->
        when (value) {
            is JsonNull -> null
            is JsonPrimitive -> key to value.content
            else -> key to value.toString()
        }
    }.toMap(),
    createdAt = createdAt,
)

// ── Domain → wire ──

internal fun SharedExpense.toWire() = ExpenseWire(
    id = id, groupId = groupId, description = description, amount = wireAmount(amount, currency), currency = currency,
    date = date.toString(), category = category, notes = notes, splitMethod = splitMethod.name,
    payers = payers.map { PayerWire(it.memberId, wireAmount(it.amount, currency)) },
    shares = shares.map { ShareWire(it.memberId, wireAmount(it.amount, currency), it.weight) },
    recurringId = recurringId, createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev,
)

internal fun SharedSettlement.toWire() = SettlementWire(
    id = id, groupId = groupId, fromMemberId = fromMemberId, toMemberId = toMemberId,
    amount = wireAmount(amount, currency), currency = currency, date = date.toString(), notes = notes,
    createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, version = version, rev = rev,
)

internal fun ExpenseTemplate.toWire() = TemplateWire(
    description = description, amount = wireAmount(amount, currency), currency = currency,
    category = category, notes = notes, splitMethod = splitMethod.name,
    payers = payers.map { PayerWire(it.memberId, wireAmount(it.amount, currency)) },
    shares = shares.map { ShareWire(it.memberId, wireAmount(it.amount, currency), it.weight) },
)

internal fun SharedRecurring.toWire() = RecurringWire(
    id = id, groupId = groupId, template = template.toWire(), interval = interval.name, anchorDay = anchorDay,
    nextDate = nextDate.toString(), createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev,
)

internal fun SharedExpense.toBody(baseVersion: Int) = ExpenseBody(
    baseVersion = baseVersion, description = description.trim(), amount = wireAmount(amount, currency),
    currency = currency, date = date.toString(), category = category?.trim().orNullIfBlank(),
    notes = notes.orNullIfBlank(), splitMethod = splitMethod.name,
    payers = payers.map { PayerWire(it.memberId, wireAmount(it.amount, currency)) },
    shares = shares.map { ShareWire(it.memberId, wireAmount(it.amount, currency), it.weight) },
    recurringId = recurringId,
)

internal fun SharedSettlement.toBody(baseVersion: Int) = SettlementBody(
    baseVersion = baseVersion, fromMemberId = fromMemberId, toMemberId = toMemberId,
    amount = wireAmount(amount, currency), currency = currency, date = date.toString(), notes = notes.orNullIfBlank(),
)

internal fun SharedRecurring.toBody(baseVersion: Int) = RecurringBody(
    baseVersion = baseVersion,
    template = template.toWire().let { it.copy(description = it.description.trim(), category = it.category?.trim().orNullIfBlank(), notes = it.notes.orNullIfBlank()) },
    interval = interval.name, nextDate = nextDate.toString(), anchorDay = anchorDay,
)

/** JSON snapshots of rows, for the outbox's copy of the server's version. */
internal object SharedEntityCodec {
    fun encode(entity: SharedEntity): String = when (entity) {
        is SharedEntity.Expense -> SharedJson.encodeToString(ExpenseWire.serializer(), entity.value.toWire())
        is SharedEntity.Settlement -> SharedJson.encodeToString(SettlementWire.serializer(), entity.value.toWire())
        is SharedEntity.Recurring -> SharedJson.encodeToString(RecurringWire.serializer(), entity.value.toWire())
    }

    fun decode(type: SharedEntityType, json: String?): SharedEntity? {
        if (json == null) return null
        return runCatching {
            when (type) {
                SharedEntityType.EXPENSE -> SharedJson.decodeFromString(ExpenseWire.serializer(), json).toDomain()?.let(SharedEntity::Expense)
                SharedEntityType.SETTLEMENT -> SharedJson.decodeFromString(SettlementWire.serializer(), json).toDomain()?.let(SharedEntity::Settlement)
                SharedEntityType.RECURRING -> SharedJson.decodeFromString(RecurringWire.serializer(), json).toDomain()?.let(SharedEntity::Recurring)
            }
        }.getOrNull()
    }
}
