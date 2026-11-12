package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedInvitePreview
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.data.sharedexpenses.SharedServerCode
import com.pennywiseai.tracker.data.sharedexpenses.retryable
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where joining stands. */
sealed interface JoinStep {
    data object Loading : JoinStep

    /** The invite is good: show the group (and seat) and the Join button, or Open group when [SharedInvitePreview.alreadyMember]. */
    data class Ready(val preview: SharedInvitePreview) : JoinStep

    /** This phone already holds a different seat in the group (`already_member`). */
    data class AlreadyMember(val groupId: String, val groupName: String) : JoinStep

    /** The invite is for [inviteServer]. Switching is offered; with groups on this phone it asks first, as it removes them. */
    data class OtherServer(val inviteServer: String, val hasGroups: Boolean) : JoinStep

    /** Could not preview. [canRetry] for a passing failure (no connection, a busy server). */
    data class Failed(val error: UiText, val canRetry: Boolean) : JoinStep
}

data class SharedJoinUiState(
    val step: JoinStep = JoinStep.Loading,
    /** No display name is stored, so the screen asks for one. */
    val needsName: Boolean = false,
    val busy: Boolean = false,
    /** Why the last Join tap did not work, shown under the button; the step stays. */
    val joinError: UiText? = null,
    val joinErrorIsName: Boolean = false,
    /** Waiting for the user to confirm switching servers, which wipes this phone's groups. */
    val confirmSwitch: Boolean = false,
    /** A group to open now; the screen clears it. */
    val openGroupId: String? = null,
)

@HiltViewModel
class SharedJoinViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    private val server: String = savedStateHandle.get<String>("server").orEmpty()
    private val token: String = savedStateHandle.get<String>("token").orEmpty()

    private val _uiState = MutableStateFlow(SharedJoinUiState(needsName = repository.identityState.value.displayName == null))
    val uiState: StateFlow<SharedJoinUiState> = _uiState.asStateFlow()

    init {
        preview()
    }

    fun preview() {
        _uiState.update { it.copy(step = JoinStep.Loading, joinError = null) }
        viewModelScope.launch {
            val step = when (val r = repository.previewInvite(server, token)) {
                is SharedResult.Success -> JoinStep.Ready(r.value)
                is SharedResult.Failure -> failureStep(r.error)
            }
            _uiState.update { it.copy(step = step, needsName = repository.identityState.value.displayName == null) }
        }
    }

    /** Joins as [name] (used only when no display name is stored), then opens the group. */
    fun join(name: String?) {
        val preview = (_uiState.value.step as? JoinStep.Ready)?.preview ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, joinError = null, joinErrorIsName = false) }
        viewModelScope.launch {
            val typed = name?.takeIf { _uiState.value.needsName }
            when (val r = repository.acceptInvite(server, token, typed)) {
                is SharedResult.Success -> _uiState.update { it.copy(busy = false, openGroupId = r.value.groupId) }
                is SharedResult.Failure -> {
                    val e = r.error
                    _uiState.update {
                        when {
                            e is SharedError.Server && e.code == SharedServerCode.ALREADY_MEMBER ->
                                it.copy(busy = false, step = JoinStep.AlreadyMember(preview.groupId, preview.groupName))
                            e is SharedError.Server && e.code == SharedServerCode.GONE ->
                                it.copy(busy = false, step = JoinStep.Failed(sharedErrorText(e), canRetry = false))
                            e is SharedError.OtherServer -> it.copy(busy = false, step = failureStep(e))
                            e == SharedError.DisplayNameRequired -> it.copy(busy = false, needsName = true, joinError = sharedErrorText(e), joinErrorIsName = true)
                            e is SharedError.Invalid -> it.copy(busy = false, joinError = sharedErrorText(e), joinErrorIsName = true)
                            else -> it.copy(busy = false, joinError = sharedErrorText(e))
                        }
                    }
                }
            }
        }
    }

    /** "Open group" when already a member: sync first, so the group is on this phone. */
    fun openGroup(groupId: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.syncNow()
            _uiState.update { it.copy(busy = false, openGroupId = groupId) }
        }
    }

    /** "Use that server": asks first when this phone has groups, since switching removes them. */
    fun useOtherServer() {
        val step = _uiState.value.step as? JoinStep.OtherServer ?: return
        if (step.hasGroups) _uiState.update { it.copy(confirmSwitch = true) } else switchServer()
    }

    fun dismissSwitch() = _uiState.update { it.copy(confirmSwitch = false) }

    fun switchServer() {
        val step = _uiState.value.step as? JoinStep.OtherServer ?: return
        _uiState.update { it.copy(confirmSwitch = false, busy = true) }
        viewModelScope.launch {
            val r = repository.setServerAddress(step.inviteServer)
            _uiState.update { it.copy(busy = false) }
            when (r) {
                is SharedResult.Success -> preview()
                is SharedResult.Failure -> _uiState.update { it.copy(step = JoinStep.Failed(sharedErrorText(r.error), canRetry = false)) }
            }
        }
    }

    fun onGroupOpened() = _uiState.update { it.copy(openGroupId = null) }

    private suspend fun failureStep(error: SharedError): JoinStep = when (error) {
        is SharedError.OtherServer -> JoinStep.OtherServer(error.inviteServer, hasGroups = repository.groups().first().isNotEmpty())
        else -> JoinStep.Failed(sharedErrorText(error), canRetry = error.retryable)
    }
}
