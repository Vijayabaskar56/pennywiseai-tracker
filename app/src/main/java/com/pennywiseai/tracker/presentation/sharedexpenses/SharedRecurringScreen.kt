package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.navigation.SharedExpenseEditor
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.CurrencyFormatter

/** A group's recurring expenses; tap one to edit its template, or stop it. */
@Composable
fun SharedRecurringScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedRecurringViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var stopping by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.gone) { if (state.gone) onNavigateBack() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_recurring_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading || state.gone -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                PennyWiseEmptyState(
                    icon = Icons.Default.Repeat,
                    headline = stringResource(R.string.shared_recurring_empty_title),
                    description = stringResource(R.string.shared_recurring_empty_body),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = Dimensions.Padding.content,
                    end = Dimensions.Padding.content,
                    top = Spacing.sm,
                    bottom = Spacing.Layout.scrollBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Layout.groupedListGap),
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { i, row ->
                    GroupedRow(
                        position = ListItemPosition.from(i, state.rows.size),
                        onClick = { onNavigate(SharedExpenseEditor(groupId, recurringId = row.id)) },
                        enabled = !state.busy,
                        modifier = Modifier.animateItem(),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(
                                    row.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                SyncMarkIcon(row.mark)
                            }
                            Text(
                                recurringSubtitle(row).asString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(CurrencyFormatter.formatCurrency(row.amount, row.currency), style = PennyWiseText.amountRow)
                        IconButton(onClick = { stopping = row.id }, enabled = !state.busy, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                Icons.Default.StopCircle,
                                contentDescription = stringResource(R.string.shared_recurring_stop_named, row.description),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    stopping?.let { id ->
        val row = state.rows.firstOrNull { it.id == id }
        if (row == null) {
            // Stopped elsewhere meanwhile: nothing left to confirm.
            LaunchedEffect(id) { stopping = null }
        } else {
            SharedConfirmDialog(
                title = stringResource(R.string.shared_recurring_stop_title, row.description),
                message = stringResource(R.string.shared_recurring_stop_body),
                confirmLabel = stringResource(R.string.shared_recurring_stop),
                onConfirm = {
                    stopping = null
                    viewModel.stop(id)
                },
                onDismiss = { stopping = null },
                destructive = true,
            )
        }
    }
}
