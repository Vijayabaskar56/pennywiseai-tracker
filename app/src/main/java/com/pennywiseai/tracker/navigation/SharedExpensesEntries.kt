package com.pennywiseai.tracker.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedActivityScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedExpenseDetailScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedExpenseEditorScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedGroupDetailScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedGroupSettingsScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedGroupsScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedJoinScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedMembersScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedProblemsScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedRecurringScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedSettingsScreen
import com.pennywiseai.tracker.presentation.sharedexpenses.SharedSettleUpScreen

/** How a host moves between Shared screens: its own back stack's push, pop and replace. */
class SharedNavigator(
    /** Opens [NavKey] on top (single top, like [navigate]). */
    val open: (NavKey) -> Unit,
    /** Back from the screen [NavKey], only while it is on top. */
    val back: (NavKey) -> Unit,
    /** Puts the second key where the first one is (Join → the joined group). */
    val replace: (from: NavKey, to: NavKey) -> Unit,
)

/**
 * The Shared expenses screens. The Shared tab registers all of them
 * ([withTabRoot]); the root host registers all but the tab root, for an Invite
 * link opened from outside the app.
 */
fun EntryProviderScope<NavKey>.sharedExpensesEntries(nav: SharedNavigator, withTabRoot: Boolean) {
    if (withTabRoot) {
        entry<SharedGroups> {
            SharedGroupsScreen(onNavigate = nav.open)
        }
    }
    entry<SharedGroupDetail> { key ->
        SharedGroupDetailScreen(groupId = key.groupId, onNavigateBack = { nav.back(key) }, onNavigate = nav.open)
    }
    entry<SharedMembers> { key ->
        SharedMembersScreen(groupId = key.groupId, onNavigateBack = { nav.back(key) })
    }
    entry<SharedGroupSettings> { key ->
        SharedGroupSettingsScreen(groupId = key.groupId, onNavigateBack = { nav.back(key) })
    }
    entry<SharedSettings> { key ->
        SharedSettingsScreen(onNavigateBack = { nav.back(key) })
    }
    entry<SharedJoin> { key ->
        SharedJoinScreen(
            server = key.server,
            token = key.token,
            onNavigateBack = { nav.back(key) },
            onOpenGroup = { groupId -> nav.replace(key, SharedGroupDetail(groupId)) },
        )
    }
    entry<SharedExpenseEditor> { key ->
        SharedExpenseEditorScreen(
            groupId = key.groupId,
            expenseId = key.expenseId,
            fromTransactionId = key.fromTransactionId,
            recurringId = key.recurringId,
            onNavigateBack = { nav.back(key) },
            onNavigate = nav.open,
        )
    }
    entry<SharedExpenseDetail> { key ->
        SharedExpenseDetailScreen(groupId = key.groupId, expenseId = key.expenseId, onNavigateBack = { nav.back(key) }, onNavigate = nav.open)
    }
    entry<SharedSettleUp> { key ->
        SharedSettleUpScreen(
            groupId = key.groupId,
            fromMemberId = key.fromMemberId,
            toMemberId = key.toMemberId,
            amount = key.amount,
            currency = key.currency,
            settlementId = key.settlementId,
            onNavigateBack = { nav.back(key) },
            onNavigate = nav.open,
        )
    }
    entry<SharedActivityFeed> { key ->
        SharedActivityScreen(groupId = key.groupId, onNavigateBack = { nav.back(key) }, onNavigate = nav.open)
    }
    entry<SharedRecurringList> { key ->
        SharedRecurringScreen(groupId = key.groupId, onNavigateBack = { nav.back(key) }, onNavigate = nav.open)
    }
    entry<SharedProblems> { key ->
        SharedProblemsScreen(onNavigateBack = { nav.back(key) }, onNavigate = nav.open)
    }
}
