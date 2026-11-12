package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
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

/** One recurring expense as the list shows it. */
data class RecurringRow(
    val id: String,
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val interval: RecurringInterval,
    val nextDate: LocalDate,
    val mark: SyncMark,
)

/** Recurring expenses still running, next due first, then by description. */
fun recurringRows(recurring: List<SharedRecurring>, problems: Set<String> = emptySet()): List<RecurringRow> =
    recurring.filter { it.deletedAt == null }
        .sortedWith(compareBy<SharedRecurring> { it.nextDate }.thenBy { it.template.description.lowercase() }.thenBy { it.id })
        .map {
            RecurringRow(
                id = it.id,
                description = it.template.description,
                amount = it.template.amount,
                currency = it.template.currency,
                interval = it.interval,
                nextDate = it.nextDate,
                mark = when {
                    it.id in problems -> SyncMark.PROBLEM
                    it.pending -> SyncMark.PENDING
                    else -> SyncMark.NONE
                },
            )
        }

@StringRes
fun intervalRes(interval: RecurringInterval): Int = when (interval) {
    RecurringInterval.WEEKLY -> R.string.shared_repeat_weekly
    RecurringInterval.MONTHLY -> R.string.shared_repeat_monthly
    RecurringInterval.YEARLY -> R.string.shared_repeat_yearly
}

/** A row's second line: "Monthly · next 1 Dec 2026". */
fun recurringSubtitle(row: RecurringRow): UiText =
    UiText.Res(R.string.shared_recurring_row, listOf(UiText.Res(intervalRes(row.interval)), formDate(row.nextDate)))

// ── ViewModel ──

data class SharedRecurringUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    val rows: List<RecurringRow> = emptyList(),
    val busy: Boolean = false,
    val message: UiText? = null,
)

@HiltViewModel
class SharedRecurringViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)

    val uiState: StateFlow<SharedRecurringUiState> = combine(
        repository.group(groupId),
        repository.recurring(groupId),
        repository.outboxProblems,
        busy,
        message,
    ) { detail, recurring, problems, b, m ->
        if (detail == null) return@combine SharedRecurringUiState(loading = false, gone = true)
        SharedRecurringUiState(loading = false, rows = recurringRows(recurring, problemIds(problems, groupId)), busy = b, message = m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedRecurringUiState())

    /** Stops [recurringId]; expenses it already created stay. */
    fun stop(recurringId: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                (repository.stopRecurring(groupId, recurringId) as? SharedResult.Failure)?.let { message.value = sharedErrorText(it.error) }
            } finally {
                busy.value = false
            }
        }
    }

    fun clearMessage() = message.update { null }
}
