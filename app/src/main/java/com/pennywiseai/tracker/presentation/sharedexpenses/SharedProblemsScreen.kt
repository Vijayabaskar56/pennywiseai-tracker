package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing

/** Every change, in every group, that the server refused or that clashes with someone else's edit. */
@Composable
fun SharedProblemsScreen(
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedProblemsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_problems_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                PennyWiseEmptyState(
                    icon = Icons.Default.CloudDone,
                    headline = stringResource(R.string.shared_problems_empty_title),
                    description = stringResource(R.string.shared_problems_empty_body),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = Dimensions.Padding.content,
                    end = Dimensions.Padding.content,
                    top = Spacing.sm,
                    bottom = Spacing.Layout.scrollBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Layout.listGap),
            ) {
                items(state.rows, key = { it.key }) { row ->
                    val open = row.openKey()
                    SharedProblemCard(
                        row = row,
                        busy = state.busyItemId != null,
                        onKeepMine = { viewModel.keepMine(row.itemId) },
                        onUseTheirs = { viewModel.useTheirs(row.itemId) },
                        onDiscard = { viewModel.discard(row.itemId) },
                        onOpen = open?.let { { onNavigate(it) } },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
