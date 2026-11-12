package com.pennywiseai.tracker.presentation.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedTransactionTie
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A transaction's tie to a Shared group (docs/shared-expenses.md, "Link to your budget") on its detail screen. */
@HiltViewModel
class TransactionSharedViewModel @Inject constructor(private val shared: SharedExpensesRepository) : ViewModel() {

    private val transaction = MutableStateFlow<TransactionEntity?>(null)

    fun setTransaction(txn: TransactionEntity?) {
        transaction.value = txn
    }

    /** How the transaction is tied to a Shared group, or null. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tie: StateFlow<SharedTransactionTie?> = transaction
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.transactionHash == b?.transactionHash }
        .flatMapLatest { t -> if (t == null) flowOf(null) else shared.observeTie(t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shared groups where this phone has a seat. */
    val groups: StateFlow<List<SharedGroup>> = shared.groups()
        .map { list -> list.filter { it.myMemberId != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unlink() {
        val id = transaction.value?.id ?: return
        viewModelScope.launch { shared.unlinkTransaction(id) }
    }
}

/** "Split with group": an expense not split, not a loan and not tied to a Shared group. */
fun canSplitWithGroup(txn: TransactionEntity, tie: SharedTransactionTie?): Boolean =
    tie == null && txn.transactionType == TransactionType.EXPENSE && txn.loanId == null && txn.lentSharesTotal == null

/** "Record as settle up": a debit or credit not split, not a loan and not tied to a Shared group. */
fun canRecordSettleUp(txn: TransactionEntity, tie: SharedTransactionTie?): Boolean =
    tie == null && (txn.transactionType == TransactionType.EXPENSE || txn.transactionType == TransactionType.INCOME) &&
        txn.loanId == null && txn.lentSharesTotal == null
