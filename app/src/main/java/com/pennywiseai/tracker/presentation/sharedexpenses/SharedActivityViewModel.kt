package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.core.ResText
import com.pennywiseai.tracker.data.sharedexpenses.SharedActivityLines
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesRepository
import com.pennywiseai.tracker.data.sharedexpenses.SharedResult
import com.pennywiseai.tracker.data.sharedexpenses.TypedText
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.ui.UiText
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
fun actorText(actorId: String?, myMemberId: String?, names: Map<String, String>): UiText =
    SharedActivityLines.actor(actorId, myMemberId, names).toUiText()

/** One feed line (SharedActivityLines.line), such as "Member A added Groceries, ₹1,240.00". */
fun activityText(activity: SharedActivity, myMemberId: String?, names: Map<String, String>): UiText =
    SharedActivityLines.line(activity, myMemberId, names).toUiText()

private fun Any.toUiText(): UiText = when (this) {
    is ResText -> UiText.Res(id, args.map { if (it is ResText || it is TypedText) it.toUiText() else it })
    is TypedText -> UiText.Plain(value)
    else -> UiText.Plain(toString())
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
