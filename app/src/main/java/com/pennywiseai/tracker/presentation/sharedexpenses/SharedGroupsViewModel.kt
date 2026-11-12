package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.preferences.CurrencyPreferences
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedInviteLink
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.BalanceSummary
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A dialog the Shared tab shows. */
sealed interface SharedGroupsDialog {
    /** Ask for a display name first, then show [then]. */
    data class Name(val then: SharedGroupsDialog) : SharedGroupsDialog

    data object NewGroup : SharedGroupsDialog

    data object Join : SharedGroupsDialog
}

/** What the Shared tab shows besides the groups. */
data class SharedGroupsForm(
    val dialog: SharedGroupsDialog? = null,
    val busy: Boolean = false,
    /** The open dialog's (or the address field's) error. */
    val fieldError: UiText? = null,
    val message: UiText? = null,
    /** A group to open (just created); the screen clears it. */
    val openGroupId: String? = null,
    /** An Invite link to open on the Join screen; the screen clears it. */
    val openInvite: SharedInviteLink? = null,
)

data class SharedGroupsUiState(
    val loading: Boolean = true,
    /** False until a server address is entered (and none is built in). */
    val addressSet: Boolean = true,
    val displayName: String? = null,
    val groups: List<SharedGroupCard> = emptyList(),
    val summary: BalanceSummary = BalanceSummary(emptyMap(), emptyMap()),
    val syncing: Boolean = false,
    val syncError: SharedError? = null,
    val problemCount: Int = 0,
    /** The default currency offered for a new group. */
    val baseCurrency: String = "INR",
    val form: SharedGroupsForm = SharedGroupsForm(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SharedGroupsViewModel @Inject constructor(
    private val repository: SharedExpensesRepository,
    currencyPreferences: CurrencyPreferences,
) : ViewModel() {

    private val form = MutableStateFlow(SharedGroupsForm())

    private val cards: Flow<List<SharedGroupCard>> = repository.groups()
        .map { groups -> groups.filter { it.deletedAt == null } }
        .flatMapLatest { groups ->
            if (groups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    groups.map { g ->
                        combine(repository.group(g.id), repository.expenses(g.id), repository.settlements(g.id)) { detail, expenses, settlements ->
                            groupCard(detail?.group ?: g, detail?.members.orEmpty(), expenses, settlements)
                        }
                    },
                ) { it.toList() }
            }
        }

    private val status = combine(repository.identityState, repository.syncState, repository.outboxProblems, currencyPreferences.baseCurrency) { identity, sync, problems, base ->
        SharedGroupsUiState(
            addressSet = identity.addressSet,
            displayName = identity.displayName,
            syncing = sync.syncing,
            syncError = sync.lastError,
            problemCount = problems.size,
            baseCurrency = base,
        )
    }

    val uiState: StateFlow<SharedGroupsUiState> = combine(status, cards, form) { s, groups, f ->
        s.copy(loading = false, groups = groups, summary = summarizeGroups(groups), form = f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedGroupsUiState())

    /** The tab came to the front: sync (docs/shared-expenses.md, "When it runs"). */
    fun onShown() {
        if (repository.identityState.value.addressSet) refresh()
    }

    /** Pull-to-refresh and Retry. Errors land in the sync state, which the banner shows. */
    fun refresh() {
        if (repository.syncState.value.syncing) return
        viewModelScope.launch { repository.syncNow() }
    }

    fun setServerAddress(address: String) = action { r ->
        when (val result = repository.setServerAddress(address)) {
            is SharedResult.Success -> {
                r.update { it.copy(fieldError = null) }
                repository.syncNow()
            }
            is SharedResult.Failure -> r.update { it.copy(fieldError = sharedErrorText(result.error)) }
        }
    }

    /** New group, asking for a display name first when none is set. */
    fun startNewGroup() = openNeedingName(SharedGroupsDialog.NewGroup)

    /** Join with a link, asking for a display name first when none is set. */
    fun startJoin() = openNeedingName(SharedGroupsDialog.Join)

    private fun openNeedingName(dialog: SharedGroupsDialog) = form.update {
        it.copy(dialog = if (repository.identityState.value.displayName == null) SharedGroupsDialog.Name(dialog) else dialog, fieldError = null)
    }

    fun dismissDialog() = form.update { if (it.busy) it else it.copy(dialog = null, fieldError = null) }

    fun setDisplayName(name: String) = action { r ->
        val then = (r.value.dialog as? SharedGroupsDialog.Name)?.then
        when (val result = repository.setDisplayName(name)) {
            is SharedResult.Success -> r.update { it.copy(dialog = then, fieldError = null) }
            is SharedResult.Failure -> r.update { it.copy(fieldError = sharedErrorText(result.error)) }
        }
    }

    fun createGroup(name: String, currency: String) = action { r ->
        when (val result = repository.createGroup(name, currency)) {
            is SharedResult.Success -> r.update { it.copy(dialog = null, fieldError = null, openGroupId = result.value.id) }
            is SharedResult.Failure -> when (result.error) {
                // The name was cleared elsewhere (Shared settings) since the dialog opened.
                SharedError.DisplayNameRequired -> r.update { it.copy(dialog = SharedGroupsDialog.Name(SharedGroupsDialog.NewGroup), fieldError = null) }
                else -> r.update { it.copy(fieldError = sharedErrorText(result.error)) }
            }
        }
    }

    /** Reads a pasted Invite link (either form) and opens the Join screen for it. */
    fun joinWithLink(text: String) {
        val link = SharedInviteLink.parse(text)
        form.update {
            if (link == null) it.copy(fieldError = UiText.Res(R.string.shared_join_link_invalid))
            else it.copy(dialog = null, fieldError = null, openInvite = link)
        }
    }

    fun onGroupOpened() = form.update { it.copy(openGroupId = null) }

    fun onInviteOpened() = form.update { it.copy(openInvite = null) }

    fun clearMessage() = form.update { it.copy(message = null) }

    private fun action(block: suspend (MutableStateFlow<SharedGroupsForm>) -> Unit) {
        if (form.value.busy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(form)
            } finally {
                form.update { it.copy(busy = false) }
            }
        }
    }
}
