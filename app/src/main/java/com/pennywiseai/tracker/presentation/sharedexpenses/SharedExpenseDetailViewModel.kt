package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkedTransaction
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

// ── Pure parts ──

/** One payer or Share line: who, how much, and for a Share what was typed ([weight]: a percent or a share count). */
data class PartLine(val memberId: String, val who: PersonRef, val amount: BigDecimal, val weight: String? = null)

/** A Shared expense as its detail screen shows it. */
data class ExpenseDetail(
    val id: String,
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val category: String?,
    val notes: String?,
    val method: SplitMethod,
    val payers: List<PartLine>,
    val shares: List<PartLine>,
    val createdBy: PersonRef,
    val createdAt: Long,
    val updatedBy: PersonRef,
    val updatedAt: Long,
    /** Changed after it was added. */
    val edited: Boolean,
    val pending: Boolean,
    val deleted: Boolean,
    /** Created by a recurring expense. */
    val fromRecurring: Boolean,
)

/** [expense] with names: payers and Shares with me first, then by name. Weights only for PERCENT and SHARES. */
fun expenseDetail(expense: SharedExpense, myMemberId: String?, names: Map<String, String>): ExpenseDetail {
    fun order(lines: List<PartLine>) = lines.sortedWith(
        compareBy<PartLine> { it.who != PersonRef.Me }.thenBy { (it.who as? PersonRef.Named)?.name?.lowercase().orEmpty() }.thenBy { it.memberId },
    )
    val weighted = expense.splitMethod == SplitMethod.PERCENT || expense.splitMethod == SplitMethod.SHARES
    return ExpenseDetail(
        id = expense.id,
        description = expense.description,
        amount = expense.amount,
        currency = expense.currency,
        date = expense.date,
        category = expense.category,
        notes = expense.notes,
        method = expense.splitMethod,
        payers = order(expense.payers.map { PartLine(it.memberId, personRef(it.memberId, myMemberId, names), it.amount) }),
        shares = order(
            expense.shares.map {
                PartLine(it.memberId, personRef(it.memberId, myMemberId, names), it.amount, if (weighted) it.weight else null)
            },
        ),
        createdBy = personRef(expense.createdBy, myMemberId, names),
        createdAt = expense.createdAt,
        updatedBy = personRef(expense.updatedBy, myMemberId, names),
        updatedAt = expense.updatedAt,
        edited = expense.version > 1 || expense.updatedAt > expense.createdAt,
        pending = expense.pending,
        deleted = expense.deletedAt != null,
        fromRecurring = expense.recurringId != null,
    )
}

// ── ViewModel ──

data class SharedExpenseDetailUiState(
    val loading: Boolean = true,
    /** Not on this phone (never sent and then deleted, or the group left); the screen leaves. */
    val gone: Boolean = false,
    val expense: ExpenseDetail? = null,
    /** This expense's change that needs attention, if any. */
    val problem: ProblemRow? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
    /** My payment linked to this expense, if any. */
    val linked: SharedLinkedTransaction? = null,
    /** I paid on it, it is not deleted and nothing is linked yet. */
    val canLink: Boolean = false,
    val picker: LinkPickerState? = null,
)

@HiltViewModel
class SharedExpenseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()
    val expenseId: String = savedStateHandle.get<String>("expenseId").orEmpty()

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)
    private val picker = SharedLinkPicker(viewModelScope, repository)

    private val data = combine(
        repository.group(groupId),
        repository.expense(expenseId),
        repository.outboxProblems,
    ) { detail, expense, problems ->
        if (detail == null || expense == null || expense.groupId != groupId) return@combine SharedExpenseDetailUiState(loading = false, gone = true)
        val me = detail.group.myMemberId
        val names = detail.members.associate { it.id to it.displayName }
        SharedExpenseDetailUiState(
            loading = false,
            expense = expenseDetail(expense, me, names),
            problem = problems.firstOrNull { it.entityType == SharedEntityType.EXPENSE && it.entityId == expenseId }
                ?.let { problemRow(it, me, names, detail.group.name) },
        )
    }

    val uiState: StateFlow<SharedExpenseDetailUiState> = combine(
        data,
        busy,
        message,
        repository.linkedTransaction(SharedEntityType.EXPENSE, expenseId),
        picker.state,
    ) { d, b, m, linked, p ->
        val e = d.expense
        val iPaid = e != null && e.payers.any { it.who == PersonRef.Me }
        d.copy(busy = b, message = m, linked = linked, canLink = iPaid && !e!!.deleted && linked == null, picker = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedExpenseDetailUiState())

    fun delete() = run { repository.deleteExpense(groupId, expenseId) }

    fun restore() = run { repository.restoreExpense(groupId, expenseId) }

    fun keepMine(itemId: Long) = run { repository.resolveConflict(itemId, keepMine = true) }

    fun useTheirs(itemId: Long) = run { repository.resolveConflict(itemId, keepMine = false) }

    fun discard(itemId: Long) = run { repository.discardChange(itemId) }

    fun clearMessage() = message.update { null }

    fun openLinkPicker() {
        val e = uiState.value.expense ?: return
        val mine = e.payers.filter { it.who == PersonRef.Me }.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
        picker.open(e.currency, credit = false, amount = mine)
    }

    fun closeLinkPicker() = picker.close()

    fun link(transactionId: Long) {
        picker.close()
        run { repository.linkExpense(transactionId, expenseId) }
    }

    fun unlink() {
        val id = uiState.value.linked?.transactionId ?: return
        run { repository.unlinkTransaction(id) }
    }

    private fun run(block: suspend () -> SharedResult<Unit>) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                (block() as? SharedResult.Failure)?.let { message.value = sharedErrorText(it.error) }
            } finally {
                busy.value = false
            }
        }
    }
}
