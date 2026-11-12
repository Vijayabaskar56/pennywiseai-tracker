package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedInviteInfo
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
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

sealed interface SharedMembersDialog {
    data object AddSeat : SharedMembersDialog

    data class Rename(val seat: SeatRow) : SharedMembersDialog

    data class Remove(val seat: SeatRow) : SharedMembersDialog
}

/** A just-made Invite link: shown once, with Share and Copy. [seatName] is null for an open invite. */
data class CreatedInvite(val url: String, val seatName: String?)

/** The Members screen's own state: dialogs, invites (online only) and messages. */
data class SharedMembersForm(
    val dialog: SharedMembersDialog? = null,
    val busy: Boolean = false,
    val fieldError: UiText? = null,
    val message: UiText? = null,
    val invites: List<SharedInviteInfo> = emptyList(),
    val invitesLoading: Boolean = true,
    val invitesError: UiText? = null,
    val createdInvite: CreatedInvite? = null,
    /** Set when a new invite should open the share sheet; the screen clears it. */
    val shareInvite: CreatedInvite? = null,
)

data class SharedMembersUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    val groupName: String = "",
    val seats: List<SeatRow> = emptyList(),
    val inviteRows: List<InviteRow> = emptyList(),
    val form: SharedMembersForm = SharedMembersForm(),
)

@HiltViewModel
class SharedMembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val form = MutableStateFlow(SharedMembersForm())

    val uiState: StateFlow<SharedMembersUiState> = combine(repository.group(groupId), form) { detail, f ->
        if (detail == null) return@combine SharedMembersUiState(loading = false, gone = true, form = f)
        val names = detail.members.associate { it.id to it.displayName }
        SharedMembersUiState(
            loading = false,
            groupName = detail.group.name,
            seats = seatRows(detail.members, detail.group.myMemberId),
            inviteRows = inviteRows(f.invites, names),
            form = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedMembersUiState())

    init {
        loadInvites()
    }

    fun loadInvites() {
        form.update { it.copy(invitesLoading = true, invitesError = null) }
        viewModelScope.launch {
            when (val r = repository.listInvites(groupId)) {
                is SharedResult.Success -> form.update { it.copy(invites = r.value, invitesLoading = false) }
                is SharedResult.Failure -> form.update { it.copy(invitesLoading = false, invitesError = sharedErrorText(r.error)) }
            }
        }
    }

    fun showDialog(dialog: SharedMembersDialog) = form.update { it.copy(dialog = dialog, fieldError = null) }

    fun dismissDialog() = form.update { if (it.busy) it else it.copy(dialog = null, fieldError = null) }

    fun addSeat(name: String) = action {
        when (val r = repository.addSeat(groupId, name)) {
            is SharedResult.Success -> form.update { it.copy(dialog = null, message = UiText.Res(R.string.shared_members_added, listOf(r.value.displayName))) }
            is SharedResult.Failure -> form.update { it.copy(fieldError = sharedErrorText(r.error)) }
        }
    }

    fun rename(seatId: String, name: String) = action {
        when (val r = repository.renameMember(groupId, seatId, name)) {
            is SharedResult.Success -> form.update { it.copy(dialog = null) }
            is SharedResult.Failure -> form.update { it.copy(fieldError = sharedErrorText(r.error)) }
        }
    }

    /** Removes [seatId]; the server refuses unless every balance of that member is zero ("settle up first"). */
    fun remove(seatId: String) = action {
        when (val r = repository.removeMember(groupId, seatId)) {
            is SharedResult.Success -> {
                form.update { it.copy(dialog = null, message = UiText.Res(R.string.shared_members_removed)) }
                loadInvites()
            }
            is SharedResult.Failure -> form.update { it.copy(dialog = null, message = sharedErrorText(r.error)) }
        }
    }

    /** A seat invite for [seat], or an open invite when null; opens the share sheet on success. */
    fun createInvite(seat: SeatRow?) = action {
        when (val r = repository.createInvite(groupId, seat?.id)) {
            is SharedResult.Success -> {
                val created = CreatedInvite(r.value.url, seat?.name)
                form.update { it.copy(createdInvite = created, shareInvite = created) }
                loadInvites()
            }
            is SharedResult.Failure -> form.update { it.copy(message = sharedErrorText(r.error)) }
        }
    }

    fun revokeInvite(inviteId: String) = action {
        when (val r = repository.revokeInvite(groupId, inviteId)) {
            is SharedResult.Success -> {
                form.update { f -> f.copy(invites = f.invites.filterNot { it.id == inviteId }, message = UiText.Res(R.string.shared_invite_revoked)) }
            }
            is SharedResult.Failure -> form.update { it.copy(message = sharedErrorText(r.error)) }
        }
    }

    fun onShareOpened() = form.update { it.copy(shareInvite = null) }

    fun dismissCreatedInvite() = form.update { it.copy(createdInvite = null) }

    fun clearMessage() = form.update { it.copy(message = null) }

    private fun action(block: suspend () -> Unit) {
        if (form.value.busy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                form.update { it.copy(busy = false) }
            }
        }
    }
}
