package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.CategoryEntity
import com.pennywiseai.tracker.data.repository.CategoryRepository
import com.pennywiseai.tracker.data.repository.TransactionRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the editor is doing: adding, editing an expense, or editing a recurring expense's template. */
enum class EditorMode { NEW, EDIT_EXPENSE, EDIT_RECURRING }

data class SharedExpenseEditorUiState(
    val loading: Boolean = true,
    /** The group, expense or recurring expense is not on this phone (any more); the screen leaves. */
    val gone: Boolean = false,
    val mode: EditorMode = EditorMode.NEW,
    val members: List<EditorMember> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val form: ExpenseForm = ExpenseForm(),
    val preview: ExpensePreview = ExpensePreview(),
    /** The bank transaction this form was filled from, kept for linking at save time (a later task). */
    val fromTransactionId: Long? = null,
    val busy: Boolean = false,
    /** Saved on this phone; the screen goes back. */
    val saved: Boolean = false,
    val message: UiText? = null,
) {
    val canSave: Boolean get() = !loading && !busy && preview.draft != null
}

@HiltViewModel
class SharedExpenseEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()
    private val expenseId: String? = savedStateHandle.get<String>("expenseId")
    private val recurringId: String? = savedStateHandle.get<String>("recurringId")
    private val fromTransactionId: Long? = savedStateHandle.get<Long>("fromTransactionId")

    private val mode = when {
        recurringId != null -> EditorMode.EDIT_RECURRING
        expenseId != null -> EditorMode.EDIT_EXPENSE
        else -> EditorMode.NEW
    }

    /** Removed members the stored row names; the form offers them too. */
    private val named = MutableStateFlow<Set<String>>(emptySet())
    private val form = MutableStateFlow<ExpenseForm?>(null)
    private val status = MutableStateFlow(Status())

    private data class Status(val gone: Boolean = false, val busy: Boolean = false, val saved: Boolean = false, val message: UiText? = null)

    private val members = combine(repository.group(groupId), named) { detail, n ->
        detail?.let { editorMembers(it.members, it.group.myMemberId, n) }
    }

    val uiState: StateFlow<SharedExpenseEditorUiState> = combine(
        members,
        form,
        status,
        categoryRepository.getVisibleExpenseCategories(),
    ) { m, f, s, categories ->
        if (s.gone) return@combine SharedExpenseEditorUiState(loading = false, gone = true, mode = mode)
        if (m == null || f == null) return@combine SharedExpenseEditorUiState(mode = mode, busy = s.busy)
        SharedExpenseEditorUiState(
            loading = false,
            mode = mode,
            members = m,
            categories = categories,
            form = f,
            preview = previewExpense(f, m),
            fromTransactionId = fromTransactionId,
            busy = s.busy,
            saved = s.saved,
            message = s.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedExpenseEditorUiState(mode = mode))

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val detail = repository.group(groupId).first() ?: return setGone()
        val me = detail.group.myMemberId
        when (mode) {
            EditorMode.EDIT_EXPENSE -> {
                val expense = repository.expense(expenseId!!).first()
                if (expense == null || expense.groupId != groupId || expense.deletedAt != null) return setGone()
                val ids = (expense.payers.map { it.memberId } + expense.shares.map { it.memberId }).toSet()
                named.value = ids
                form.value = expenseForm(expense, editorMembers(detail.members, me, ids))
            }
            EditorMode.EDIT_RECURRING -> {
                val recurring = repository.recurring(groupId).first().firstOrNull { it.id == recurringId } ?: return setGone()
                val t = recurring.template
                val ids = (t.payers.map { it.memberId } + t.shares.map { it.memberId }).toSet()
                named.value = ids
                form.value = recurringForm(t, recurring.interval, recurring.nextDate, editorMembers(detail.members, me, ids))
            }
            EditorMode.NEW -> {
                var f = newExpenseForm(detail.group, editorMembers(detail.members, me), LocalDate.now())
                fromTransactionId?.let { id ->
                    // Read only: the transaction is not linked or changed here.
                    transactionRepository.getTransactionById(id)?.let { t ->
                        f = f.fromTransaction(t.merchantName, t.amount, t.currency, t.dateTime.toLocalDate(), t.category)
                    }
                }
                form.value = f
            }
        }
    }

    private fun setGone() = status.update { it.copy(gone = true) }

    private fun edit(section: FormField? = null, change: (ExpenseForm) -> ExpenseForm) {
        form.update { f -> f?.let(change)?.let { if (section != null) it.touch(section) else it } }
    }

    // ── Fields ──

    fun setDescription(value: String) = edit(FormField.DESCRIPTION) { it.copy(description = value) }

    /** Ignored when it isn't a number with the currency's decimals, so the field keeps what was there. */
    fun setAmount(value: String) {
        val f = form.value ?: return
        if (acceptNumberInput(value, currencyDecimals(f.currency))) edit(FormField.AMOUNT) { it.copy(amount = value) }
    }

    fun setCurrency(code: String) = edit(FormField.CURRENCY) { it.copy(currency = code) }

    fun setDate(date: LocalDate) = edit { it.copy(date = date) }

    fun setCategory(name: String?) = edit(FormField.CATEGORY) { it.copy(category = name) }

    fun setNotes(value: String) = edit(FormField.NOTES) { it.copy(notes = value) }

    // ── Paid by ──

    fun setPayer(memberId: String) = edit(FormField.PAYERS) { it.copy(paidBy = PaidBy.ONE, payerId = memberId) }

    /** Several people paid; starts from the one payer paying the whole amount. */
    fun setSeveralPayers() = edit(FormField.PAYERS) { f ->
        val start = if (f.payerAmounts.isEmpty() && f.payerId != null && f.amount.isNotBlank()) mapOf(f.payerId to f.amount) else f.payerAmounts
        f.copy(paidBy = PaidBy.SEVERAL, payerAmounts = start)
    }

    fun setPayerAmount(memberId: String, value: String) {
        val f = form.value ?: return
        if (acceptNumberInput(value, currencyDecimals(f.currency))) {
            edit(FormField.PAYERS) { it.copy(payerAmounts = it.payerAmounts + (memberId to value)) }
        }
    }

    // ── Split ──

    fun setMethod(method: SplitMethod) = edit(FormField.SHARES) { it.copy(method = method) }

    fun toggleEqual(memberId: String) = edit(FormField.SHARES) {
        it.copy(equalIds = if (memberId in it.equalIds) it.equalIds - memberId else it.equalIds + memberId)
    }

    fun setExactAmount(memberId: String, value: String) {
        val f = form.value ?: return
        if (acceptNumberInput(value, currencyDecimals(f.currency))) {
            edit(FormField.SHARES) { it.copy(exactAmounts = it.exactAmounts + (memberId to value)) }
        }
    }

    fun setPercent(memberId: String, value: String) {
        if (acceptNumberInput(value, PERCENT_DECIMALS)) edit(FormField.SHARES) { it.copy(percents = it.percents + (memberId to value)) }
    }

    fun changeShareCount(memberId: String, delta: Int) = edit(FormField.SHARES) {
        val next = ((it.shareCounts[memberId] ?: 0) + delta).coerceIn(0, MAX_SHARE_COUNT)
        it.copy(shareCounts = it.shareCounts + (memberId to next))
    }

    fun setRepeat(interval: RecurringInterval?) = edit { it.copy(repeat = interval) }

    fun clearMessage() = status.update { it.copy(message = null) }

    // ── Save ──

    /** Saves on this phone (queued for the server, so it works offline), then the screen goes back. */
    fun save() {
        val f = form.value ?: return
        if (status.value.busy || uiState.value.loading) return
        val draft = previewExpense(f, uiState.value.members).draft
        if (draft == null) {
            // Show every problem, untouched sections included.
            edit { it.copy(touched = FormField.entries.toSet()) }
            return
        }
        status.update { it.copy(busy = true) }
        viewModelScope.launch {
            val error = try {
                when (mode) {
                    EditorMode.EDIT_RECURRING -> repository.saveRecurring(groupId, recurringId, draft, f.repeat ?: RecurringInterval.MONTHLY, f.date).errorOrNull()
                    EditorMode.EDIT_EXPENSE -> when (val r = repository.saveExpense(groupId, expenseId, draft, f.date)) {
                        is SharedResult.Success -> null.also { onSaved(r.value, fromTransactionId) }
                        is SharedResult.Failure -> r.error
                    }
                    EditorMode.NEW -> {
                        val repeat = f.repeat
                        if (repeat == null) {
                            when (val r = repository.saveExpense(groupId, null, draft, f.date)) {
                                is SharedResult.Success -> null.also { onSaved(r.value, fromTransactionId) }
                                is SharedResult.Failure -> r.error
                            }
                        } else {
                            when (val r = repository.saveRecurring(groupId, null, draft, repeat, f.date)) {
                                is SharedResult.Success -> {
                                    // saveRecurring records the first expense itself.
                                    repository.expenses(groupId).first().firstOrNull { it.recurringId == r.value.id }
                                        ?.let { onSaved(it, fromTransactionId) }
                                    null
                                }
                                is SharedResult.Failure -> r.error
                            }
                        }
                    }
                }
            } finally {
                status.update { it.copy(busy = false) }
            }
            if (error == null) {
                status.update { it.copy(saved = true) }
            } else {
                if (error is SharedError.Invalid) edit { it.copy(touched = FormField.entries.toSet()) }
                status.update { it.copy(message = sharedErrorText(error)) }
            }
        }
    }

    /**
     * Where linking a bank transaction attaches. Called once [expense] is saved
     * on this phone; [fromTransactionId] is the transaction the form was filled
     * from, or null. For a new recurring expense, [expense] is the first expense
     * it recorded. Does nothing yet: the transaction is left untouched until the
     * budget-link task links it here (shared_links, lent_shares_total).
     */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun onSaved(expense: SharedExpense, fromTransactionId: Long?) = Unit

    private fun SharedResult<*>.errorOrNull(): SharedError? = (this as? SharedResult.Failure)?.error
}
