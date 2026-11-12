package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkedTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** The "Link a bank transaction" picker's state, owned by a ViewModel. */
data class LinkPickerState(val currency: String, val candidates: List<SharedLinkedTransaction>? = null)

class SharedLinkPicker(private val scope: CoroutineScope, private val repository: SharedExpensesRepository) {
    private val _state = MutableStateFlow<LinkPickerState?>(null)

    /** Null while the picker is closed. */
    val state: StateFlow<LinkPickerState?> = _state.asStateFlow()

    /** Opens it on [credit] (else debit) transactions in [currency], those of [amount] first. */
    fun open(currency: String, credit: Boolean, amount: BigDecimal?) {
        _state.value = LinkPickerState(currency)
        scope.launch {
            val found = repository.linkCandidates(currency, credit, amount)
            _state.value?.takeIf { it.currency == currency }?.let { _state.value = it.copy(candidates = found) }
        }
    }

    fun close() {
        _state.value = null
    }
}
