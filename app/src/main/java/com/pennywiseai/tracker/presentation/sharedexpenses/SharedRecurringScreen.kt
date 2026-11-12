package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R

/** A group's recurring expenses. Placeholder until the list is written. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedRecurringScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    SharedPlaceholderScreen(title = stringResource(R.string.shared_recurring_title), onNavigateBack = onNavigateBack)
}
