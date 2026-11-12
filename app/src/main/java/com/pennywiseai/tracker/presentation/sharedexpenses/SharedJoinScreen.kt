package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing

/**
 * Joining through an Invite link: shows the group (and the seat, for a seat
 * invite), asks for a display name if none is stored, and joins. Every outcome
 * gets a plain message: expired or used up, already a member, another server,
 * no connection.
 */
@Composable
fun SharedJoinScreen(
    server: String,
    @Suppress("UNUSED_PARAMETER") token: String,
    onNavigateBack: () -> Unit,
    onOpenGroup: (groupId: String) -> Unit,
    viewModel: SharedJoinViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.openGroupId) {
        state.openGroupId?.let {
            viewModel.onGroupOpened()
            onOpenGroup(it)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_join_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val step = state.step) {
                JoinStep.Loading -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { LoadingIndicator() }
                is JoinStep.Ready -> ReadyContent(step, state, onJoin = viewModel::join, onOpenGroup = { viewModel.openGroup(step.preview.groupId) })
                is JoinStep.AlreadyMember -> {
                    PennyWiseEmptyState(
                        icon = Icons.Default.Groups,
                        headline = step.groupName,
                        description = stringResource(R.string.shared_join_already_member_other_seat),
                    )
                    Button(onClick = { viewModel.openGroup(step.groupId) }, enabled = !state.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shared_join_open_group))
                    }
                }
                is JoinStep.OtherServer -> {
                    PennyWiseEmptyState(
                        icon = Icons.Default.Dns,
                        headline = stringResource(R.string.shared_join_other_server_title),
                        description = stringResource(
                            if (step.hasGroups) R.string.shared_join_other_server_body_groups else R.string.shared_join_other_server_body,
                            serverHost(step.inviteServer),
                        ),
                    )
                    Button(onClick = viewModel::useOtherServer, enabled = !state.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shared_join_use_server))
                    }
                    OutlinedButton(onClick = onNavigateBack, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shared_cancel))
                    }
                }
                is JoinStep.Failed -> {
                    PennyWiseEmptyState(
                        icon = if (step.canRetry) Icons.Default.CloudOff else Icons.Default.LinkOff,
                        headline = stringResource(if (step.canRetry) R.string.shared_join_failed_retry_title else R.string.shared_join_failed_title),
                        description = step.error.asString(),
                    )
                    if (step.canRetry) {
                        Button(onClick = viewModel::preview, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.shared_retry))
                        }
                    }
                    OutlinedButton(onClick = onNavigateBack, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shared_close))
                    }
                }
            }
            if (state.step !is JoinStep.OtherServer && server.isNotEmpty()) {
                Text(
                    stringResource(R.string.shared_join_server_note, serverHost(server)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (state.confirmSwitch) {
        val step = state.step as? JoinStep.OtherServer
        SharedConfirmDialog(
            title = stringResource(R.string.shared_settings_server_confirm_title),
            message = stringResource(R.string.shared_settings_server_confirm_body, serverHost(step?.inviteServer.orEmpty())),
            confirmLabel = stringResource(R.string.shared_settings_server_confirm),
            onConfirm = viewModel::switchServer,
            onDismiss = viewModel::dismissSwitch,
            destructive = true,
        )
    }
}

@Composable
private fun ReadyContent(step: JoinStep.Ready, state: SharedJoinUiState, onJoin: (String?) -> Unit, onOpenGroup: () -> Unit) {
    val preview = step.preview
    PennyWiseEmptyState(
        icon = Icons.Default.Groups,
        headline = preview.groupName,
        description = when {
            preview.alreadyMember -> stringResource(R.string.shared_join_already_member)
            preview.memberDisplayName != null -> stringResource(R.string.shared_join_as_seat, preview.memberDisplayName)
            else -> stringResource(R.string.shared_join_open_body)
        },
    )
    if (preview.alreadyMember) {
        Button(onClick = onOpenGroup, enabled = !state.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.shared_join_open_group))
        }
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    val join = { if (!state.busy && (!state.needsName || name.isNotBlank())) onJoin(name) }
    if (state.needsName) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.shared_name_label)) },
            singleLine = true,
            isError = state.joinErrorIsName,
            supportingText = {
                Text(if (state.joinErrorIsName) state.joinError?.asString().orEmpty() else stringResource(R.string.shared_name_dialog_body))
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { join() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = join,
        enabled = !state.busy && (!state.needsName || name.isNotBlank()),
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (state.busy) R.string.shared_join_joining else R.string.shared_join_button))
    }
    if (state.joinError != null && !state.joinErrorIsName) {
        Text(state.joinError.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}
