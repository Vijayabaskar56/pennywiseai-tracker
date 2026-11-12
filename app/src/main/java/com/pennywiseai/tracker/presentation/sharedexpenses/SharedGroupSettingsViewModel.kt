package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.SharedBalances
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SharedGroupSettingsDialog { RENAME, CURRENCY, LEAVE, DELETE }

data class SharedGroupSettingsForm(
    val dialog: SharedGroupSettingsDialog? = null,
    val busy: Boolean = false,
    val fieldError: UiText? = null,
    val message: UiText? = null,
)

data class SharedGroupSettingsUiState(
    val loading: Boolean = true,
    /** The group left this phone (left, deleted or removed); the screen steps back. */
    val gone: Boolean = false,
    val name: String = "",
    val defaultCurrency: String = "",
    val simplifyDebts: Boolean = true,
    /** My balance per currency; leaving is possible only when it is empty (settled up everywhere). */
    val myLines: List<BalanceLine> = emptyList(),
    val form: SharedGroupSettingsForm = SharedGroupSettingsForm(),
) {
    val canLeave: Boolean get() = myLines.isEmpty()
}

@HiltViewModel
class SharedGroupSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val form = MutableStateFlow(SharedGroupSettingsForm())

    val uiState: StateFlow<SharedGroupSettingsUiState> = combine(
        repository.group(groupId),
        repository.expenses(groupId),
        repository.settlements(groupId),
        form,
    ) { detail, expenses, settlements, f ->
        if (detail == null) return@combine SharedGroupSettingsUiState(loading = false, gone = true, form = f)
        val g = detail.group
        val mine = g.myMemberId?.let { SharedBalances.forMember(SharedBalances.balances(expenses, settlements), it) }.orEmpty()
        SharedGroupSettingsUiState(
            loading = false,
            name = g.name,
            defaultCurrency = g.defaultCurrency,
            simplifyDebts = g.simplifyDebts,
            myLines = balanceLines(mine),
            form = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedGroupSettingsUiState())

    fun showDialog(dialog: SharedGroupSettingsDialog) = form.update { it.copy(dialog = dialog, fieldError = null) }

    fun dismissDialog() = form.update { if (it.busy) it else it.copy(dialog = null, fieldError = null) }

    fun rename(name: String) = action(inDialog = true) { repository.renameGroup(groupId, name) }

    fun setDefaultCurrency(currency: String) = action { repository.updateGroupSettings(groupId, defaultCurrency = currency) }

    fun setSimplifyDebts(on: Boolean) = action { repository.updateGroupSettings(groupId, simplifyDebts = on) }

    /** Leaves (only at a zero balance; the server says `balance_not_zero` otherwise). The group then leaves this phone. */
    fun leave() = action { repository.leaveGroup(groupId) }

    /** Deletes the group for every member. */
    fun delete() = action { repository.deleteGroup(groupId) }

    fun clearMessage() = form.update { it.copy(message = null) }

    /** Runs [block]; a failure stays in the open dialog when [inDialog], else closes it and shows a message. */
    private fun action(inDialog: Boolean = false, block: suspend () -> SharedResult<*>) {
        if (form.value.busy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val r = block()) {
                    is SharedResult.Success -> form.update { it.copy(dialog = null, fieldError = null) }
                    is SharedResult.Failure -> form.update {
                        if (inDialog) it.copy(fieldError = sharedErrorText(r.error))
                        else it.copy(dialog = null, message = sharedErrorText(r.error))
                    }
                }
            } finally {
                form.update { it.copy(busy = false) }
            }
        }
    }
}
