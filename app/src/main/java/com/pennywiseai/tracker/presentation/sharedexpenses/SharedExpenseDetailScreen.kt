package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R

/** One Shared expense. Placeholder until the screen is written. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedExpenseDetailScreen(
    groupId: String,
    expenseId: String,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    SharedPlaceholderScreen(title = stringResource(R.string.shared_expense_detail_title), onNavigateBack = onNavigateBack)
}
