package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkedTransaction
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.data.sharedexpenses.SharedUpiIds
import com.pennywiseai.tracker.domain.loan.UpiRequest
import com.pennywiseai.tracker.domain.sharedexpenses.Debt
import com.pennywiseai.tracker.domain.sharedexpenses.SettlementDraft
import com.pennywiseai.tracker.domain.sharedexpenses.SharedBalances
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMoney
import com.pennywiseai.tracker.domain.sharedexpenses.SharedValidation
import com.pennywiseai.tracker.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

// ── Pure parts ──

/** The settle-up form as typed: [fromId] paid [toId]. The amount stays the typed text. */
data class SettleForm(
    val fromId: String? = null,
    val toId: String? = null,
    val amount: String = "",
    val currency: String = "INR",
    val date: LocalDate = LocalDate.of(2000, 1, 1),
    val notes: String = "",
    /** The user set the amount (or it is a stored Settlement): changing the pair or currency leaves it alone. */
    val amountEdited: Boolean = false,
    /** Shows the problem inline once the user has changed something. */
    val touched: Boolean = false,
)

/** What [from] owes [to] in [currency] among [debts] (keyed by currency), or null. */
fun debtBetween(debts: Map<String, List<Debt>>, from: String?, to: String?, currency: String): BigDecimal? =
    debts[currency]?.firstOrNull { it.fromMemberId == from && it.toMemberId == to }?.amount

/**
 * The form a new Settlement opens with. With a pair from the navigation key, that
 * pair; with one side only (opened from a bank transaction), the other side is
 * the member that side owes or is owed most, else another member; otherwise me paying the member I owe most, else the member who owes me
 * most paying me, else me paying the first other member. Debts in the group's
 * [defaultCurrency] come first, then the largest. The amount is the key's, else
 * the debt between the two in that currency, else blank.
 */
fun settleUpDefaults(
    myMemberId: String?,
    members: List<EditorMember>,
    debts: Map<String, List<Debt>>,
    defaultCurrency: String,
    date: LocalDate,
    keyFrom: String? = null,
    keyTo: String? = null,
    keyAmount: String? = null,
    keyCurrency: String? = null,
): SettleForm {
    val ids = members.map { it.id }.toSet()
    val firstOther = members.firstOrNull { !it.removed && it.id != myMemberId }?.id
    val currency = keyCurrency?.takeIf { SharedMoney.strictFractionDigits(it) != null }
    val all = debts.values.flatten()
    val preference = compareBy<Debt> { it.currency != (currency ?: defaultCurrency) }.thenByDescending { it.amount }.thenBy { it.toMemberId }.thenBy { it.fromMemberId }

    val pick: Debt? = when {
        keyFrom != null || keyTo != null -> null
        else -> all.filter { it.fromMemberId == myMemberId && it.toMemberId in ids }.minWithOrNull(preference)
            ?: all.filter { it.toMemberId == myMemberId && it.fromMemberId in ids }.minWithOrNull(preference)
    }
    val givenFrom = keyFrom?.takeIf { it in ids }
    val givenTo = keyTo?.takeIf { it in ids }
    // Only the receiver is known (a payment I received): the payer is whoever owes them most, else anyone else.
    val payerFor = givenTo?.takeIf { givenFrom == null }?.let { receiver ->
        all.filter { it.toMemberId == receiver && it.fromMemberId in ids }.minWithOrNull(preference)?.fromMemberId
            ?: members.firstOrNull { !it.removed && it.id != receiver }?.id
    }
    val from = givenFrom ?: pick?.fromMemberId ?: payerFor ?: myMemberId?.takeIf { it in ids }
    // Only the payer is known (a payment I made): the receiver is whoever they owe most.
    val owedBy = givenFrom?.takeIf { givenTo == null }?.let { payer ->
        all.filter { it.fromMemberId == payer && it.toMemberId in ids }.minWithOrNull(preference)?.toMemberId
    }
    val to = givenTo ?: pick?.toMemberId ?: owedBy ?: (if (from == firstOther) myMemberId else firstOther)
    val chosenCurrency = currency
        ?: pick?.currency
        ?: all.filter { it.fromMemberId == from && it.toMemberId == to }.minWithOrNull(preference)?.currency
        ?: defaultCurrency
    val typed = keyAmount?.let(::canonicalNumber)
    val amount = typed ?: debtBetween(debts, from, to, chosenCurrency)?.let(::amountFieldText).orEmpty()
    return SettleForm(fromId = from, toId = to, amount = amount, currency = chosenCurrency, date = date, amountEdited = typed != null)
}

/** [form] with the amount refilled from the debt between the pair, unless the user set it. */
fun SettleForm.refill(debts: Map<String, List<Debt>>): SettleForm =
    if (amountEdited) this else copy(amount = debtBetween(debts, fromId, toId, currency)?.let(::amountFieldText).orEmpty())

/** What [form] would save among [memberIds], or the first problem. */
fun checkSettlement(form: SettleForm, memberIds: Set<String>): Pair<SettlementDraft?, SharedExpenseError?> {
    val from = form.fromId ?: return null to SharedExpenseError.UNKNOWN_MEMBER
    val to = form.toId ?: return null to SharedExpenseError.UNKNOWN_MEMBER
    val amount = parseNumberInput(form.amount) ?: return null to SharedExpenseError.AMOUNT_NOT_POSITIVE
    val draft = SettlementDraft(from, to, amount, form.currency, form.notes)
    val error = SharedValidation.validateSettlement(draft, memberIds)
    return (if (error == null) draft else null) to error
}

/** "Pay with UPI" applies when I am paying someone else in INR. */
fun canPayWithUpi(form: SettleForm, myMemberId: String?): Boolean =
    myMemberId != null && form.fromId == myMemberId && form.toId != null && form.toId != myMemberId && form.currency == "INR"

// ── ViewModel ──

data class SharedSettleUpUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** Editing a recorded Settlement (offers Delete). */
    val editing: Boolean = false,
    val members: List<EditorMember> = emptyList(),
    val form: SettleForm = SettleForm(),
    /** The first problem; shown once [SettleForm.touched]. */
    val error: SharedExpenseError? = null,
    val canPayUpi: Boolean = false,
    /** The receiver's remembered UPI ID, when [canPayUpi]. */
    val upiId: String? = null,
    val busy: Boolean = false,
    val saved: Boolean = false,
    /** Why the picked bank transaction was not linked although the payment was saved. */
    val savedNotice: UiText? = null,
    val message: UiText? = null,
    /** My payment already linked to the Settlement being edited. */
    val linked: SharedLinkedTransaction? = null,
    /** A payment picked here (or the one this was opened from), linked when it is saved. */
    val pendingLink: SharedLinkedTransaction? = null,
    /** I paid or received it and nothing is linked or picked. */
    val canLink: Boolean = false,
    val picker: LinkPickerState? = null,
) {
    val canSave: Boolean get() = !loading && !busy && error == null
    val toName: String? get() = members.firstOrNull { it.id == form.toId }?.name
}

@HiltViewModel
class SharedSettleUpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
    private val upiIds: SharedUpiIds,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()
    private val settlementId: String? = savedStateHandle.get<String>("settlementId")
    private val keyFrom: String? = savedStateHandle.get<String>("fromMemberId")
    private val keyTo: String? = savedStateHandle.get<String>("toMemberId")
    private val keyAmount: String? = savedStateHandle.get<String>("amount")
    private val keyCurrency: String? = savedStateHandle.get<String>("currency")
    private val fromTransactionId: Long? = savedStateHandle.get<Long>("fromTransactionId")

    private val pendingLink = MutableStateFlow<SharedLinkedTransaction?>(null)
    private val picker = SharedLinkPicker(viewModelScope, repository)

    private data class LinkUi(val linked: SharedLinkedTransaction?, val pending: SharedLinkedTransaction?, val picker: LinkPickerState?)

    private val linkUi = combine(
        if (settlementId != null) repository.linkedTransaction(SharedEntityType.SETTLEMENT, settlementId) else flowOf(null),
        pendingLink,
        picker.state,
    ) { l, p, pk -> LinkUi(l, p, pk) }

    private val named = MutableStateFlow<Set<String>>(emptySet())
    private val form = MutableStateFlow<SettleForm?>(null)
    private val upi = MutableStateFlow<Map<String, String>>(emptyMap())
    private val status = MutableStateFlow(Status())

    private data class Status(
        val gone: Boolean = false,
        val busy: Boolean = false,
        val saved: Boolean = false,
        val savedNotice: UiText? = null,
        val message: UiText? = null,
    )

    private data class GroupData(val name: String, val me: String?, val members: List<EditorMember>, val debts: Map<String, List<Debt>>)

    private val groupData = combine(
        repository.group(groupId),
        repository.expenses(groupId),
        repository.settlements(groupId),
        named,
    ) { detail, expenses, settlements, n ->
        detail?.let {
            GroupData(it.group.name, it.group.myMemberId, editorMembers(it.members, it.group.myMemberId, n), SharedBalances.debts(it.group.simplifyDebts, expenses, settlements))
        }
    }

    private val groupState = groupData.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The latest debts, for refilling the amount when the pair or currency changes. */
    private val debts: Map<String, List<Debt>> get() = groupState.value?.debts.orEmpty()

    val uiState: StateFlow<SharedSettleUpUiState> = combine(groupState, form, upi, status, linkUi) { g, f, u, s, link ->
        if (s.gone) return@combine SharedSettleUpUiState(loading = false, gone = true)
        if (g == null || f == null) return@combine SharedSettleUpUiState(editing = settlementId != null)
        val upiOk = canPayWithUpi(f, g.me)
        SharedSettleUpUiState(
            loading = false,
            editing = settlementId != null,
            members = g.members,
            form = f,
            error = checkSettlement(f, g.members.map { it.id }.toSet()).second,
            canPayUpi = upiOk,
            upiId = if (upiOk) f.toId?.let(u::get) else null,
            busy = s.busy,
            saved = s.saved,
            savedNotice = s.savedNotice,
            message = s.message,
            linked = link.linked,
            pendingLink = link.pending,
            canLink = g.me != null && (f.fromId == g.me || f.toId == g.me) && link.linked == null && link.pending == null,
            picker = link.picker,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedSettleUpUiState(editing = settlementId != null))

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val detail = repository.group(groupId).first() ?: return setGone()
        val me = detail.group.myMemberId
        val loaded = if (settlementId != null) {
            val s = repository.settlements(groupId).first().firstOrNull { it.id == settlementId } ?: return setGone()
            named.value = setOf(s.fromMemberId, s.toMemberId)
            SettleForm(
                fromId = s.fromMemberId, toId = s.toMemberId, amount = amountFieldText(s.amount), currency = s.currency,
                date = s.date, notes = s.notes.orEmpty(), amountEdited = true,
            )
        } else {
            val data = groupData.first() ?: return setGone()
            // Opened from a bank transaction: its amount, currency and date, me as payer of a debit or receiver of a credit.
            val t = fromTransactionId?.let { repository.linkableTransaction(it) }
            t?.let { pendingLink.value = it.linked }
            val from = keyFrom ?: me?.takeIf { t != null && !t.credit }
            val to = keyTo ?: me?.takeIf { t != null && t.credit }
            settleUpDefaults(
                me, data.members, data.debts, detail.group.defaultCurrency, t?.linked?.date ?: LocalDate.now(), from, to,
                keyAmount ?: t?.linked?.amount?.toPlainString(), keyCurrency ?: t?.linked?.currency,
            )
        }
        form.value = loaded
        loadUpi(loaded.toId)
    }

    private fun setGone() = status.update { it.copy(gone = true) }

    private fun loadUpi(memberId: String?) {
        if (memberId == null || memberId in upi.value) return
        viewModelScope.launch { upiIds.get(memberId)?.let { id -> upi.update { it + (memberId to id) } } }
    }

    private fun edit(change: (SettleForm) -> SettleForm) = form.update { f -> f?.let(change)?.copy(touched = true) }

    /** Picks who paid; picking the receiver swaps the two. */
    fun setFrom(memberId: String) {
        edit { f -> (if (f.toId == memberId) f.copy(fromId = memberId, toId = f.fromId) else f.copy(fromId = memberId)).refill(debts) }
        loadUpi(form.value?.toId)
    }

    /** Picks who received; picking the payer swaps the two. */
    fun setTo(memberId: String) {
        edit { f -> (if (f.fromId == memberId) f.copy(toId = memberId, fromId = f.toId) else f.copy(toId = memberId)).refill(debts) }
        loadUpi(memberId)
    }

    fun swap() = edit { f -> f.copy(fromId = f.toId, toId = f.fromId).refill(debts) }

    fun setAmount(value: String) {
        val f = form.value ?: return
        if (acceptNumberInput(value, currencyDecimals(f.currency))) edit { it.copy(amount = value, amountEdited = value.isNotBlank()) }
    }

    fun setCurrency(code: String) = edit { it.copy(currency = code).refill(debts) }

    fun setDate(date: LocalDate) = edit { it.copy(date = date) }

    fun setNotes(value: String) = edit { it.copy(notes = value) }

    fun clearMessage() = status.update { it.copy(message = null) }

    // ── Link a bank transaction ──

    fun openLinkPicker() {
        val f = form.value ?: return
        val me = groupState.value?.me ?: return
        picker.open(f.currency, credit = f.toId == me, amount = parseNumberInput(f.amount))
    }

    fun closeLinkPicker() = picker.close()

    /** Picks [t]; it is linked when the Settlement is saved. */
    fun pickLink(t: SharedLinkedTransaction) {
        picker.close()
        pendingLink.value = t
    }

    /** Drops the picked payment, or unlinks the one already linked (at once). */
    fun unlink() {
        if (pendingLink.value != null) {
            pendingLink.value = null
            return
        }
        val id = uiState.value.linked?.transactionId ?: return
        viewModelScope.launch {
            (repository.unlinkTransaction(id) as? SharedResult.Failure)?.let { r -> status.update { it.copy(message = sharedErrorText(r.error)) } }
        }
    }

    /** Remembers [id] as the receiver's UPI ID on this phone. False (and nothing saved) when it isn't one. */
    fun saveUpiId(id: String): Boolean {
        val to = form.value?.toId ?: return false
        if (!UpiRequest.isValidUpiId(id)) return false
        val trimmed = id.trim()
        upi.update { it + (to to trimmed) }
        viewModelScope.launch { upiIds.set(to, trimmed) }
        return true
    }

    /**
     * The `upi://pay` link for paying the receiver, or null without a UPI ID or
     * an amount above zero. The note is the form's notes, else the group's name.
     */
    fun upiUri(): String? {
        val f = form.value ?: return null
        val g = groupState.value ?: return null
        if (!canPayWithUpi(f, g.me)) return null
        val id = f.toId?.let { upi.value[it] } ?: return null
        val amount = parseNumberInput(f.amount)?.takeIf { it.signum() > 0 } ?: return null
        val payee = g.members.firstOrNull { it.id == f.toId }?.name
        return UpiRequest.payUri(id, payee, amount, f.notes.ifBlank { g.name })
    }

    /** Records (or saves the edit of) the Settlement on this phone; the screen goes back. */
    fun save() {
        val f = form.value ?: return
        if (status.value.busy || uiState.value.loading) return
        val (draft, _) = checkSettlement(f, uiState.value.members.map { it.id }.toSet())
        if (draft == null) {
            form.update { it?.copy(touched = true) }
            return
        }
        run {
            repository.saveSettlement(groupId, settlementId, draft, f.date).also { r ->
                // Linked only when I paid or received it; otherwise the transaction stays as it is.
                val pick = pendingLink.value
                if (r is SharedResult.Success && pick != null && uiState.value.linked == null) {
                    val refused = repository.linkSettlement(pick.transactionId, r.value.id) as? SharedResult.Failure
                    // The payment is saved; the screen says why its bank transaction was not linked.
                    status.update { it.copy(savedNotice = refused?.let { f -> sharedErrorText(f.error) }) }
                }
            }
        }
    }

    fun delete() {
        val id = settlementId ?: return
        run { repository.deleteSettlement(groupId, id) }
    }

    private fun run(block: suspend () -> SharedResult<*>) {
        status.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                block()
            } finally {
                status.update { it.copy(busy = false) }
            }
            when (result) {
                is SharedResult.Success -> status.update { it.copy(saved = true) }
                is SharedResult.Failure -> status.update { it.copy(message = sharedErrorText(result.error)) }
            }
        }
    }
}
