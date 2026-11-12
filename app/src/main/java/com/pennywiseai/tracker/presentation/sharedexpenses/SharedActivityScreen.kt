package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R

/** One group's activity feed, or every group's when [groupId] is null. Placeholder until the feed is written. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedActivityScreen(
    groupId: String?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    SharedPlaceholderScreen(title = stringResource(R.string.shared_activity_title), onNavigateBack = onNavigateBack)
}
