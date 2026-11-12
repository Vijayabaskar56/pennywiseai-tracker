package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.data.sharedexpenses.SharedServerAddress
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

data class SharedSettingsForm(
    val busy: Boolean = false,
    val nameError: UiText? = null,
    val addressError: UiText? = null,
    /** A new address waiting for the user to confirm that switching wipes the local copy. */
    val confirmAddress: String? = null,
    val message: UiText? = null,
)

data class SharedSettingsUiState(
    val displayName: String = "",
    val serverAddress: String = "",
    val syncing: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastError: SharedError? = null,
    val form: SharedSettingsForm = SharedSettingsForm(),
)

@HiltViewModel
class SharedSettingsViewModel @Inject constructor(
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    private val form = MutableStateFlow(SharedSettingsForm())

    val uiState: StateFlow<SharedSettingsUiState> = combine(repository.identityState, repository.syncState, form) { identity, sync, f ->
        SharedSettingsUiState(
            displayName = identity.displayName.orEmpty(),
            serverAddress = identity.serverAddress,
            syncing = sync.syncing,
            lastSuccessAt = sync.lastSuccessAt,
            lastError = sync.lastError,
            form = f,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SharedSettingsUiState(
            displayName = repository.identityState.value.displayName.orEmpty(),
            serverAddress = repository.identityState.value.serverAddress,
        ),
    )

    fun saveDisplayName(name: String) = action {
        when (val r = repository.setDisplayName(name)) {
            is SharedResult.Success -> form.update { it.copy(nameError = null, message = UiText.Res(R.string.shared_settings_name_saved)) }
            is SharedResult.Failure -> form.update { it.copy(nameError = sharedErrorText(r.error)) }
        }
    }

    /**
     * Checks [address]; switching from a server already in use asks first,
     * since it removes this phone's copy of its groups.
     */
    fun requestServerAddress(address: String) {
        val normalized = SharedServerAddress.normalize(address)
        val current = repository.identityState.value.serverAddress
        when {
            normalized == null -> form.update { it.copy(addressError = sharedErrorText(SharedError.InvalidServerAddress)) }
            normalized == current -> form.update { it.copy(addressError = null, message = UiText.Res(R.string.shared_settings_server_same)) }
            current.isEmpty() -> applyServerAddress(normalized)
            else -> form.update { it.copy(addressError = null, confirmAddress = normalized) }
        }
    }

    fun dismissAddressConfirm() = form.update { it.copy(confirmAddress = null) }

    fun confirmServerAddress() {
        val address = form.value.confirmAddress ?: return
        form.update { it.copy(confirmAddress = null) }
        applyServerAddress(address)
    }

    private fun applyServerAddress(address: String) = action {
        when (val r = repository.setServerAddress(address)) {
            is SharedResult.Success -> {
                form.update { it.copy(addressError = null, message = UiText.Res(R.string.shared_settings_server_saved)) }
                repository.syncNow()
            }
            is SharedResult.Failure -> form.update { it.copy(addressError = sharedErrorText(r.error)) }
        }
    }

    fun syncNow() {
        if (repository.syncState.value.syncing) return
        viewModelScope.launch { repository.syncNow() }
    }

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
