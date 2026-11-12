package com.pennywiseai.tracker.presentation.sharedexpenses

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.navigation.SharedExpenseDetail
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing

/** One group's activity feed, or every group's when [groupId] is null (the ViewModel reads it from the key). */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedActivityScreen(
    groupId: String?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.gone) { if (state.gone) onNavigateBack() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_activity_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading || state.gone -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                PennyWiseEmptyState(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    headline = stringResource(R.string.shared_activity_empty_title),
                    description = stringResource(R.string.shared_activity_empty_body),
                )
            }
            else -> {
                val now = remember(state.rows) { System.currentTimeMillis() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = Dimensions.Padding.content,
                        end = Dimensions.Padding.content,
                        top = Spacing.sm,
                        bottom = Spacing.Layout.scrollBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Layout.groupedListGap),
                ) {
                    itemsIndexed(state.rows, key = { _, row -> row.key }) { i, row ->
                        ActivityLine(
                            row = row,
                            now = now,
                            position = ListItemPosition.from(i, state.rows.size),
                            busy = state.busy,
                            onOpen = row.expenseId?.let { id -> { onNavigate(SharedExpenseDetail(row.groupId, id)) } },
                            onRestore = { viewModel.restore(row) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLine(
    row: ActivityRow,
    now: Long,
    position: ListItemPosition,
    busy: Boolean,
    onOpen: (() -> Unit)?,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupedRow(position = position, onClick = onOpen, modifier = modifier) {
        Icon(activityIcon(row.activity.type), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(activityText(row.activity, row.myMemberId, row.names).asString(), style = MaterialTheme.typography.bodyMedium)
            val time = DateUtils.getRelativeTimeSpanString(row.activity.createdAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
            Text(
                row.groupName?.let { stringResource(R.string.shared_activity_meta_group, it, time) } ?: time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.canRestore) {
            TextButton(onClick = onRestore, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_restore)) }
        }
    }
}

private fun activityIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.GROUP_CREATED, ActivityType.GROUP_DELETED, ActivityType.GROUP_RENAMED, ActivityType.GROUP_SETTINGS_CHANGED -> Icons.Default.Group
    ActivityType.MEMBER_ADDED, ActivityType.MEMBER_JOINED, ActivityType.MEMBER_REMOVED, ActivityType.MEMBER_RENAMED -> Icons.Default.Person
    ActivityType.EXPENSE_ADDED, ActivityType.EXPENSE_EDITED, ActivityType.EXPENSE_DELETED, ActivityType.EXPENSE_RESTORED -> Icons.AutoMirrored.Filled.ReceiptLong
    ActivityType.SETTLEMENT_ADDED, ActivityType.SETTLEMENT_EDITED, ActivityType.SETTLEMENT_DELETED -> Icons.Default.SwapHoriz
    ActivityType.RECURRING_ADDED, ActivityType.RECURRING_EDITED, ActivityType.RECURRING_STOPPED -> Icons.Default.Repeat
}
