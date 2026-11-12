package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.ui.UiText
import com.pennywiseai.tracker.utils.CurrencyFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Pure parts ──

/** What a feed row needs to know about its group: name, this phone's seat, and members' names. */
data class ActivityGroupInfo(
    val name: String,
    val myMemberId: String?,
    val names: Map<String, String>,
    /** Expenses on this phone that are deleted, and those that are not. */
    val deletedExpenses: Set<String> = emptySet(),
    val liveExpenses: Set<String> = emptySet(),
)

/** One line of the feed. */
data class ActivityRow(
    val activity: SharedActivity,
    val myMemberId: String?,
    val names: Map<String, String>,
    /** The group's name, shown in the all-groups feed only. */
    val groupName: String?,
    /** The expense to open when tapped; null for other rows or an expense not on this phone. */
    val expenseId: String?,
    /** The newest "deleted" row of an expense that is still deleted. */
    val canRestore: Boolean,
) {
    val key: String get() = "${activity.groupId}-${activity.seq}"
    val groupId: String get() = activity.groupId
}

private val EXPENSE_TYPES = setOf(ActivityType.EXPENSE_ADDED, ActivityType.EXPENSE_EDITED, ActivityType.EXPENSE_DELETED, ActivityType.EXPENSE_RESTORED)

/** The feed: newest first. [showGroup] names each row's group (the all-groups feed). */
fun activityRows(activities: List<SharedActivity>, groups: Map<String, ActivityGroupInfo>, showGroup: Boolean): List<ActivityRow> {
    val restoreOffered = HashSet<String>()
    return activities
        .sortedWith(compareByDescending<SharedActivity> { it.createdAt }.thenBy { it.groupId }.thenByDescending { it.seq })
        .map { a ->
            val info = groups[a.groupId]
            val isExpense = a.type in EXPENSE_TYPES
            val onPhone = info != null && (a.entityId in info.liveExpenses || a.entityId in info.deletedExpenses)
            val canRestore = a.type == ActivityType.EXPENSE_DELETED && info != null && a.entityId in info.deletedExpenses &&
                restoreOffered.add(a.entityId)
            ActivityRow(
                activity = a,
                myMemberId = info?.myMemberId,
                names = info?.names.orEmpty(),
                groupName = if (showGroup) info?.name else null,
                expenseId = if (isExpense && onPhone) a.entityId else null,
                canRestore = canRestore,
            )
        }
}

/** Who did it: "You", a member's name, or "Recurring" when the server acted (a recurring expense came due). */
fun actorText(actorId: String?, myMemberId: String?, names: Map<String, String>): UiText = when {
    actorId == null -> UiText.Res(R.string.shared_actor_recurring)
    actorId == myMemberId -> UiText.Res(R.string.shared_person_you)
    else -> names[actorId]?.let { UiText.Plain(it) } ?: UiText.Res(R.string.shared_person_unknown)
}

/** A member mid-sentence: "you", their name now, else [fallback] (the name the row recorded). */
private fun memberText(memberId: String?, myMemberId: String?, names: Map<String, String>, fallback: String? = null): UiText = when {
    memberId != null && memberId == myMemberId -> UiText.Res(R.string.shared_person_you_lower)
    else -> (fallback ?: memberId?.let(names::get))?.let { UiText.Plain(it) } ?: UiText.Res(R.string.shared_person_unknown)
}

/** The summary's amount in its currency ("₹1,240.00"), or null when it has none. */
private fun summaryMoney(summary: Map<String, String>): String? {
    val amount = summary["amount"]?.toBigDecimalOrNull() ?: return null
    val currency = summary["currency"]?.takeIf { it.isNotBlank() } ?: return null
    return CurrencyFormatter.formatCurrency(amount, currency)
}

/** "Groceries, ₹1,240.00", or just the description when the amount is missing. */
private fun subject(summary: Map<String, String>): UiText {
    val description = summary["description"]?.let { UiText.Plain(it) } ?: UiText.Res(R.string.shared_activity_an_expense)
    val money = summaryMoney(summary) ?: return description
    return UiText.Res(R.string.shared_activity_subject_amount, listOf(description, money))
}

private fun intervalText(summary: Map<String, String>): UiText =
    when (summary["interval"]?.let { raw -> RecurringInterval.entries.firstOrNull { it.name == raw } }) {
        RecurringInterval.WEEKLY -> UiText.Res(R.string.shared_activity_interval_weekly)
        RecurringInterval.MONTHLY -> UiText.Res(R.string.shared_activity_interval_monthly)
        RecurringInterval.YEARLY -> UiText.Res(R.string.shared_activity_interval_yearly)
        null -> UiText.Res(R.string.shared_activity_interval_unknown)
    }

/**
 * One feed line from [activity]'s type and summary, such as "Member A added
 * Groceries, ₹1,240.00". The actor reads "You" for this phone's seat and
 * "Recurring" when the server acted. Every [ActivityType] has its line.
 */
fun activityText(activity: SharedActivity, myMemberId: String?, names: Map<String, String>): UiText {
    val s = activity.summary
    val actor = actorText(activity.actorMemberId, myMemberId, names)
    fun res(id: Int, vararg args: Any) = UiText.Res(id, listOf(actor) + args)
    return when (activity.type) {
        ActivityType.GROUP_CREATED -> res(R.string.shared_activity_group_created, s["name"].orEmpty())
        ActivityType.GROUP_DELETED -> res(R.string.shared_activity_group_deleted, s["name"].orEmpty())
        ActivityType.GROUP_RENAMED -> s["oldName"]?.let { old -> res(R.string.shared_activity_group_renamed, old, s["name"].orEmpty()) }
            ?: res(R.string.shared_activity_group_renamed_to, s["name"].orEmpty())
        ActivityType.GROUP_SETTINGS_CHANGED -> {
            val currency = s["defaultCurrency"]
            val simplify = s["simplifyDebts"]?.toBooleanStrictOrNull()
            when {
                currency != null && simplify == null -> res(R.string.shared_activity_settings_currency, currency)
                currency == null && simplify == true -> res(R.string.shared_activity_settings_simplify_on)
                currency == null && simplify == false -> res(R.string.shared_activity_settings_simplify_off)
                else -> res(R.string.shared_activity_settings_changed)
            }
        }
        ActivityType.MEMBER_ADDED -> res(R.string.shared_activity_member_added, memberText(activity.entityId, myMemberId, names, s["memberName"]))
        ActivityType.MEMBER_JOINED -> {
            // The joiner is the actor; before this phone knows their seat, the recorded name stands in.
            val joiner = if (activity.actorMemberId == myMemberId || activity.actorMemberId in names) actor else s["memberName"]?.let { UiText.Plain(it) } ?: actor
            UiText.Res(R.string.shared_activity_member_joined, listOf(joiner))
        }
        ActivityType.MEMBER_REMOVED -> if (activity.actorMemberId != null && activity.actorMemberId == activity.entityId) {
            res(R.string.shared_activity_member_left)
        } else {
            res(R.string.shared_activity_member_removed, memberText(activity.entityId, myMemberId, names, s["memberName"]))
        }
        ActivityType.MEMBER_RENAMED -> s["oldName"]?.let { old -> res(R.string.shared_activity_member_renamed, old, s["memberName"].orEmpty()) }
            ?: res(R.string.shared_activity_member_renamed_to, s["memberName"].orEmpty())
        ActivityType.EXPENSE_ADDED -> res(R.string.shared_activity_expense_added, subject(s))
        ActivityType.EXPENSE_EDITED -> res(R.string.shared_activity_expense_edited, subject(s))
        ActivityType.EXPENSE_DELETED -> res(R.string.shared_activity_expense_deleted, subject(s))
        ActivityType.EXPENSE_RESTORED -> res(R.string.shared_activity_expense_restored, subject(s))
        ActivityType.SETTLEMENT_ADDED, ActivityType.SETTLEMENT_EDITED, ActivityType.SETTLEMENT_DELETED -> {
            val id = when (activity.type) {
                ActivityType.SETTLEMENT_ADDED -> R.string.shared_activity_settlement_added
                ActivityType.SETTLEMENT_EDITED -> R.string.shared_activity_settlement_edited
                else -> R.string.shared_activity_settlement_deleted
            }
            val money = summaryMoney(s) ?: listOfNotNull(s["amount"], s["currency"]).joinToString(" ")
            res(id, memberText(s["fromMemberId"], myMemberId, names), memberText(s["toMemberId"], myMemberId, names), money)
        }
        ActivityType.RECURRING_ADDED -> res(R.string.shared_activity_recurring_added, subject(s), intervalText(s))
        ActivityType.RECURRING_EDITED -> res(R.string.shared_activity_recurring_edited, subject(s), intervalText(s))
        ActivityType.RECURRING_STOPPED -> res(R.string.shared_activity_recurring_stopped, subject(s), intervalText(s))
    }
}

// ── ViewModel ──

data class SharedActivityUiState(
    val loading: Boolean = true,
    /** One group's feed whose group left this phone; the screen leaves. */
    val gone: Boolean = false,
    val rows: List<ActivityRow> = emptyList(),
    val busy: Boolean = false,
    val message: UiText? = null,
)

/** One group's feed ([groupId] set) or every group's. */
@HiltViewModel
class SharedActivityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedExpensesRepository,
) : ViewModel() {

    private val groupId: String? = savedStateHandle.get<String>("groupId")

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)

    private val groupIds: Flow<List<String>> =
        if (groupId != null) flowOf(listOf(groupId)) else repository.groups().map { groups -> groups.map { it.id } }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val infos: Flow<Map<String, ActivityGroupInfo>> = groupIds.flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(
                ids.map { id ->
                    combine(repository.group(id), repository.expenses(id, includeDeleted = true)) { detail, expenses ->
                        detail?.let {
                            id to ActivityGroupInfo(
                                name = it.group.name,
                                myMemberId = it.group.myMemberId,
                                names = it.members.associate { m -> m.id to m.displayName },
                                deletedExpenses = expenses.filter { e -> e.deletedAt != null }.map { e -> e.id }.toSet(),
                                liveExpenses = expenses.filter { e -> e.deletedAt == null }.map { e -> e.id }.toSet(),
                            )
                        }
                    }
                },
            ) { pairs -> pairs.filterNotNull().toMap() }
        }
    }

    private val activity: Flow<List<SharedActivity>> = if (groupId != null) repository.activity(groupId) else repository.allActivity()

    val uiState: StateFlow<SharedActivityUiState> = combine(infos, activity, busy, message) { i, a, b, m ->
        if (groupId != null && groupId !in i) return@combine SharedActivityUiState(loading = false, gone = true)
        SharedActivityUiState(loading = false, rows = activityRows(a, i, showGroup = groupId == null), busy = b, message = m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedActivityUiState())

    /** Brings back the deleted expense of [row]. */
    fun restore(row: ActivityRow) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                when (val r = repository.restoreExpense(row.groupId, row.activity.entityId)) {
                    is SharedResult.Success -> message.value = UiText.Res(R.string.shared_activity_restored)
                    is SharedResult.Failure -> message.value = sharedErrorText(r.error)
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun clearMessage() = message.update { null }
}
