package com.pennywiseai.tracker.data.sharedexpenses

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.math.BigDecimal
import java.time.LocalDate

// Room rows ↔ domain models. Rows are written only from domain models, so reading
// them back trusts their enums and numbers.

internal fun SharedGroupEntity.toDomain() = SharedGroup(
    id = id, name = name, defaultCurrency = defaultCurrency, simplifyDebts = simplifyDebts, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, rev = rev, myMemberId = myMemberId,
)

internal fun SharedGroup.toEntity(syncRev: Long) = SharedGroupEntity(
    id = id, name = name, defaultCurrency = defaultCurrency, simplifyDebts = simplifyDebts, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, rev = rev, syncRev = syncRev, myMemberId = myMemberId,
)

internal fun SharedMemberEntity.toDomain() = SharedMember(id, groupId, displayName, joined, removedAt, rev)

internal fun SharedMember.toEntity() = SharedMemberEntity(id, groupId, displayName, joined, removedAt, rev)

internal fun SharedExpenseEntity.toDomain(payers: List<SharedExpensePayerEntity>, shares: List<SharedExpenseShareEntity>) = SharedExpense(
    id = id, groupId = groupId, description = description, amount = BigDecimal(amount), currency = currency,
    date = LocalDate.parse(date), category = category, notes = notes, splitMethod = SplitMethod.valueOf(splitMethod),
    payers = payers.sortedBy { it.position }.map { PayerPart(it.memberId, BigDecimal(it.amount)) },
    shares = shares.sortedBy { it.position }.map { SharePart(it.memberId, BigDecimal(it.amount), it.weight) },
    recurringId = recurringId, createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

internal fun SharedExpense.toEntity() = SharedExpenseEntity(
    id = id, groupId = groupId, description = description, amount = amount.toPlainString(), currency = currency,
    date = date.toString(), category = category, notes = notes, splitMethod = splitMethod.name, recurringId = recurringId,
    createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    version = version, rev = rev, pending = pending,
)

internal fun SharedExpense.payerEntities() = payers.mapIndexed { i, p -> SharedExpensePayerEntity(id, p.memberId, p.amount.toPlainString(), i) }

internal fun SharedExpense.shareEntities() = shares.mapIndexed { i, s -> SharedExpenseShareEntity(id, s.memberId, s.amount.toPlainString(), s.weight, i) }

internal fun SharedSettlementEntity.toDomain() = SharedSettlement(
    id = id, groupId = groupId, fromMemberId = fromMemberId, toMemberId = toMemberId, amount = BigDecimal(amount),
    currency = currency, date = LocalDate.parse(date), notes = notes, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

internal fun SharedSettlement.toEntity() = SharedSettlementEntity(
    id = id, groupId = groupId, fromMemberId = fromMemberId, toMemberId = toMemberId, amount = amount.toPlainString(),
    currency = currency, date = date.toString(), notes = notes, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

private val payersSerializer = ListSerializer(PayerWire.serializer())
private val sharesSerializer = ListSerializer(ShareWire.serializer())
private val summarySerializer = MapSerializer(String.serializer(), String.serializer())

internal fun SharedRecurringEntity.toDomain() = SharedRecurring(
    id = id, groupId = groupId,
    template = ExpenseTemplate(
        description = description, amount = BigDecimal(amount), currency = currency, category = category, notes = notes,
        splitMethod = SplitMethod.valueOf(splitMethod),
        payers = SharedJson.decodeFromString(payersSerializer, payersJson).map { PayerPart(it.memberId, BigDecimal(it.amount)) },
        shares = SharedJson.decodeFromString(sharesSerializer, sharesJson).map { SharePart(it.memberId, BigDecimal(it.amount), it.weight) },
    ),
    interval = RecurringInterval.valueOf(interval), anchorDay = anchorDay, nextDate = LocalDate.parse(nextDate),
    createdBy = createdBy, updatedBy = updatedBy, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    version = version, rev = rev, pending = pending,
)

internal fun SharedRecurring.toEntity() = SharedRecurringEntity(
    id = id, groupId = groupId, description = template.description, amount = template.amount.toPlainString(),
    currency = template.currency, category = template.category, notes = template.notes, splitMethod = template.splitMethod.name,
    payersJson = SharedJson.encodeToString(payersSerializer, template.payers.map { PayerWire(it.memberId, it.amount.toPlainString()) }),
    sharesJson = SharedJson.encodeToString(sharesSerializer, template.shares.map { ShareWire(it.memberId, it.amount.toPlainString(), it.weight) }),
    interval = interval.name, anchorDay = anchorDay, nextDate = nextDate.toString(), createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, version = version, rev = rev, pending = pending,
)

/** Null when the stored type is no longer known to this app (never stored, but read defensively). */
internal fun SharedActivityEntity.toDomain(): SharedActivity? = SharedActivity(
    seq = seq, groupId = groupId, actorMemberId = actorMemberId,
    type = ActivityType.entries.firstOrNull { it.name == type } ?: return null, entityId = entityId,
    summary = runCatching { SharedJson.decodeFromString(summarySerializer, summaryJson) }.getOrDefault(emptyMap()),
    createdAt = createdAt,
)

internal fun SharedActivity.toEntity() = SharedActivityEntity(
    groupId = groupId, seq = seq, actorMemberId = actorMemberId, type = type.name, entityId = entityId,
    summaryJson = SharedJson.encodeToString(summarySerializer, summary), createdAt = createdAt,
)

internal fun SharedOutboxEntity.toItem() = SharedOutboxItem(
    id = id, groupId = groupId, entityType = SharedEntityType.valueOf(entityType), entityId = entityId,
    operation = SharedOperation.valueOf(operation), baseVersion = baseVersion, revision = revision, attempted = attempted,
    restoreFirst = restoreFirst, status = SharedOutboxStatus.valueOf(status), serverJson = serverJson,
    errorStatus = errorStatus, errorCode = errorCode, errorMessage = errorMessage, errorField = errorField, createdAt = createdAt,
)

internal fun SharedOutboxItem.toEntity() = SharedOutboxEntity(
    id = id, groupId = groupId, entityType = entityType.name, entityId = entityId, operation = operation.name,
    baseVersion = baseVersion, revision = revision, attempted = attempted, restoreFirst = restoreFirst, status = status.name,
    serverJson = serverJson, errorStatus = errorStatus, errorCode = errorCode, errorMessage = errorMessage,
    errorField = errorField, createdAt = createdAt,
)
