package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.domain.sharedexpenses.SharedBalances
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedGroupDetailUiState(
    val loading: Boolean = true,
    /** The group is no longer on this phone (deleted, or this phone was removed); the screen leaves. */
    val gone: Boolean = false,
    val groupName: String = "",
    val myLines: List<BalanceLine> = emptyList(),
    val debts: List<DebtLine> = emptyList(),
    val ledger: List<LedgerItem> = emptyList(),
    val syncing: Boolean = false,
    val syncError: SharedError? = null,
    /** This group's changes the server refused or that conflict. */
    val problemCount: Int = 0,
)

@HiltViewModel
class SharedGroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val data = combine(
        repository.group(groupId),
        repository.expenses(groupId),
        repository.settlements(groupId),
        repository.outboxProblems,
    ) { detail, expenses, settlements, problems ->
        if (detail == null) return@combine SharedGroupDetailUiState(loading = false, gone = true)
        val group = detail.group
        val me = group.myMemberId
        val names = detail.members.associate { it.id to it.displayName }
        val balances = SharedBalances.balances(expenses, settlements)
        val problemIds = problemIds(problems, groupId)
        SharedGroupDetailUiState(
            loading = false,
            groupName = group.name,
            myLines = if (me == null) emptyList() else balanceLines(SharedBalances.forMember(balances, me)),
            debts = debtLines(SharedBalances.debts(group.simplifyDebts, expenses, settlements), me, names),
            ledger = buildLedger(expenses, settlements, me, names, problemIds),
            problemCount = problemIds.size,
        )
    }

    val uiState: StateFlow<SharedGroupDetailUiState> = combine(data, repository.syncState) { d, sync ->
        d.copy(syncing = sync.syncing, syncError = sync.lastError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedGroupDetailUiState())

    init {
        // Opening a group syncs (docs/shared-expenses.md, "When it runs").
        refresh()
    }

    fun refresh() {
        if (repository.syncState.value.syncing) return
        viewModelScope.launch { repository.syncNow() }
    }
}
