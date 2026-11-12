package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntity
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedGroupDetail
import com.pennywiseai.tracker.data.sharedexpenses.SharedOperation
import com.pennywiseai.tracker.data.sharedexpenses.SharedOutboxProblem
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.navigation.SharedExpenseDetail
import com.pennywiseai.tracker.navigation.SharedExpenseEditor
import com.pennywiseai.tracker.navigation.SharedRecurringList
import com.pennywiseai.tracker.navigation.SharedSettleUp
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

// ── Pure parts: a queued change that needs attention, as the screens show it ──

/** One expense, Settlement or recurring expense, reduced to what a problem shows of it. */
data class EntityFacts(
    val type: SharedEntityType,
    /** The expense's or recurring expense's description; null for a Settlement. */
    val description: String?,
    val amount: BigDecimal,
    val currency: String,
    /** The expense's or Settlement's date, or a recurring expense's next date. */
    val date: LocalDate,
    /** A Settlement's payer and receiver; null otherwise. */
    val from: PersonRef? = null,
    val to: PersonRef? = null,
    /** Who changed this version last. */
    val changedBy: PersonRef,
    val deleted: Boolean,
)

fun entityFacts(entity: SharedEntity, myMemberId: String?, names: Map<String, String>): EntityFacts = when (entity) {
    is SharedEntity.Expense -> with(entity.value) {
        EntityFacts(
            SharedEntityType.EXPENSE, description, amount, currency, date,
            changedBy = personRef(updatedBy, myMemberId, names), deleted = deletedAt != null,
        )
    }
    is SharedEntity.Settlement -> with(entity.value) {
        EntityFacts(
            SharedEntityType.SETTLEMENT, null, amount, currency, date,
            from = personRef(fromMemberId, myMemberId, names), to = personRef(toMemberId, myMemberId, names),
            changedBy = personRef(updatedBy, myMemberId, names), deleted = deletedAt != null,
        )
    }
    is SharedEntity.Recurring -> with(entity.value) {
        EntityFacts(
            SharedEntityType.RECURRING, template.description, template.amount, template.currency, nextDate,
            changedBy = personRef(updatedBy, myMemberId, names), deleted = deletedAt != null,
        )
    }
}

/** Why a change needs attention. */
sealed interface ProblemKind {
    /** Someone else changed the row first: my version (null if gone from this phone) against theirs. */
    data class Conflict(val mine: EntityFacts?, val theirs: EntityFacts) : ProblemKind

    /** The server refused the change for [error]. */
    data class Failed(val error: SharedError) : ProblemKind
}

/** One change that needs attention, ready to show. */
data class ProblemRow(
    val itemId: Long,
    val groupId: String,
    /** The group's name; null when the group is not on this phone. */
    val groupName: String?,
    val entityType: SharedEntityType,
    val entityId: String,
    val operation: SharedOperation,
    /** What it is: my version, else the server's; null when neither is known. */
    val facts: EntityFacts?,
    val kind: ProblemKind,
    /** My version still exists here and is not deleted, so it can be opened and edited. */
    val mineLive: Boolean,
) {
    val key: String get() = "problem-$itemId"
}

fun problemRow(problem: SharedOutboxProblem, myMemberId: String?, names: Map<String, String>, groupName: String?): ProblemRow {
    val mine = when (problem) {
        is SharedOutboxProblem.Conflict -> problem.mine
        is SharedOutboxProblem.Failed -> problem.mine
    }
    val mineFacts = mine?.let { entityFacts(it, myMemberId, names) }
    val kind = when (problem) {
        is SharedOutboxProblem.Conflict -> ProblemKind.Conflict(mineFacts, entityFacts(problem.theirs, myMemberId, names))
        is SharedOutboxProblem.Failed -> ProblemKind.Failed(problem.error)
    }
    return ProblemRow(
        itemId = problem.itemId,
        groupId = problem.groupId,
        groupName = groupName,
        entityType = problem.entityType,
        entityId = problem.entityId,
        operation = problem.operation,
        facts = mineFacts ?: (kind as? ProblemKind.Conflict)?.theirs,
        kind = kind,
        mineLive = mine != null && mine.deletedAt == null,
    )
}

/** Every problem with its group's names, oldest change first (the outbox order). */
fun problemRows(problems: List<SharedOutboxProblem>, groups: Map<String, SharedGroupDetail>): List<ProblemRow> =
    problems.map { p ->
        val detail = groups[p.groupId]
        problemRow(p, detail?.group?.myMemberId, detail?.members?.associate { it.id to it.displayName }.orEmpty(), detail?.group?.name)
    }

/**
 * Where "Open" goes: the expense (its detail shows a deleted one too), the
 * Settlement's form, or the recurring expense's editor (the list when it is
 * gone). Null when there is nothing on this phone to open.
 */
fun ProblemRow.openKey(): NavKey? = when (entityType) {
    SharedEntityType.EXPENSE -> if (facts != null) SharedExpenseDetail(groupId, entityId) else null
    SharedEntityType.SETTLEMENT -> if (mineLive) SharedSettleUp(groupId, settlementId = entityId) else null
    SharedEntityType.RECURRING -> if (mineLive) SharedExpenseEditor(groupId, recurringId = entityId) else SharedRecurringList(groupId)
}

// ── ViewModel ──

data class SharedProblemsUiState(
    val loading: Boolean = true,
    val rows: List<ProblemRow> = emptyList(),
    /** The item an action is running for; its buttons are disabled. */
    val busyItemId: Long? = null,
    val message: UiText? = null,
)

/** Everything in the outbox that needs attention, across groups (opened from the sync banner). */
@HiltViewModel
class SharedProblemsViewModel @Inject constructor(
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    private val busy = MutableStateFlow<Long?>(null)
    private val message = MutableStateFlow<UiText?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows: Flow<List<ProblemRow>> = repository.outboxProblems.flatMapLatest { problems ->
        val groupIds = problems.map { it.groupId }.distinct()
        if (groupIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(groupIds.map { repository.group(it) }) { details ->
                problemRows(problems, details.filterNotNull().associateBy { it.group.id })
            }
        }
    }

    val uiState: StateFlow<SharedProblemsUiState> = combine(rows, busy, message) { r, b, m ->
        SharedProblemsUiState(loading = false, rows = r, busyItemId = b, message = m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedProblemsUiState())

    fun keepMine(itemId: Long) = run(itemId) { repository.resolveConflict(itemId, keepMine = true) }

    fun useTheirs(itemId: Long) = run(itemId) { repository.resolveConflict(itemId, keepMine = false) }

    fun discard(itemId: Long) = run(itemId) { repository.discardChange(itemId) }

    fun clearMessage() = message.update { null }

    private fun run(itemId: Long, block: suspend () -> SharedResult<Unit>) {
        if (busy.value != null) return
        busy.value = itemId
        viewModelScope.launch {
            try {
                (block() as? SharedResult.Failure)?.let { message.value = sharedErrorText(it.error) }
            } finally {
                busy.value = null
            }
        }
    }
}
