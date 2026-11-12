package com.pennywiseai.tracker.presentation.sharedexpenses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.AppDates
import java.time.Instant
import java.time.ZoneId

/**
 * A group's seats: who has joined, who hasn't yet, who was removed. Add a
 * person, invite them (share sheet and copy), rename or remove a seat, and see
 * and revoke the active Invite links.
 */
@Composable
fun SharedMembersScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    viewModel: SharedMembersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Someone may have joined since the last sync, so fetch seats and invites each time this shows.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // The group detail under this screen says why; just step back to it.
    LaunchedEffect(state.gone) { if (state.gone) onNavigateBack() }

    val message = form.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }
    val shareChooser = stringResource(R.string.shared_invite_share_chooser)
    val shareText = form.shareInvite?.let { stringResource(R.string.shared_invite_share_text, state.groupName, it.url) }
    LaunchedEffect(form.shareInvite) {
        if (form.shareInvite != null && shareText != null) {
            viewModel.onShareOpened()
            shareLink(context, shareText, shareChooser)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_members_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading || state.gone) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Layout.groupedListGap),
        ) {
            itemsIndexed(state.seats, key = { _, seat -> "seat-${seat.id}" }) { i, seat ->
                SeatRowItem(
                    seat = seat,
                    position = ListItemPosition.from(i, state.seats.size),
                    busy = form.busy,
                    onInvite = { viewModel.createInvite(seat) },
                    onRename = { viewModel.showDialog(SharedMembersDialog.Rename(seat)) },
                    onRemove = { viewModel.showDialog(SharedMembersDialog.Remove(seat)) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.showDialog(SharedMembersDialog.AddSeat) },
                        enabled = !form.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Text(stringResource(R.string.shared_members_add), modifier = Modifier.padding(start = Spacing.xs))
                    }
                    OutlinedButton(
                        onClick = { viewModel.createInvite(null) },
                        enabled = !form.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Text(stringResource(R.string.shared_members_invite_new), modifier = Modifier.padding(start = Spacing.xs))
                    }
                }
            }
            item(key = "invites-header") {
                SectionHeaderV2(title = stringResource(R.string.shared_invites_title), topSpacing = Spacing.md)
            }
            when {
                form.invitesLoading && state.inviteRows.isEmpty() -> item(key = "invites-loading") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                form.invitesError != null -> item(key = "invites-error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            form.invitesError.asString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::loadInvites, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_retry)) }
                    }
                }
                state.inviteRows.isEmpty() -> item(key = "invites-none") {
                    Text(
                        stringResource(R.string.shared_invites_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                }
            }
            if (form.invitesError == null) {
                itemsIndexed(state.inviteRows, key = { _, invite -> "invite-${invite.id}" }) { i, invite ->
                    InviteRowItem(
                        invite = invite,
                        position = ListItemPosition.from(i, state.inviteRows.size),
                        busy = form.busy,
                        onRevoke = { viewModel.revokeInvite(invite.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    when (val dialog = form.dialog) {
        null -> Unit
        SharedMembersDialog.AddSeat -> SharedTextFieldDialog(
            title = stringResource(R.string.shared_members_add),
            message = stringResource(R.string.shared_members_add_body),
            label = stringResource(R.string.shared_name_label),
            confirmLabel = stringResource(R.string.shared_add),
            onConfirm = viewModel::addSeat,
            onDismiss = viewModel::dismissDialog,
            error = form.fieldError?.asString(),
            busy = form.busy,
        )
        is SharedMembersDialog.Rename -> SharedTextFieldDialog(
            title = stringResource(R.string.shared_members_rename),
            label = stringResource(R.string.shared_name_label),
            confirmLabel = stringResource(R.string.shared_save),
            initial = dialog.seat.name,
            onConfirm = { viewModel.rename(dialog.seat.id, it) },
            onDismiss = viewModel::dismissDialog,
            error = form.fieldError?.asString(),
            busy = form.busy,
        )
        is SharedMembersDialog.Remove -> SharedConfirmDialog(
            title = stringResource(R.string.shared_members_remove_title, dialog.seat.name),
            message = stringResource(R.string.shared_members_remove_body),
            confirmLabel = stringResource(R.string.shared_members_remove),
            onConfirm = { viewModel.remove(dialog.seat.id) },
            onDismiss = viewModel::dismissDialog,
            destructive = true,
        )
    }

    form.createdInvite?.let { invite ->
        CreatedInviteDialog(
            invite = invite,
            onShare = { shareText(context, invite, state.groupName, shareChooser) },
            onDismiss = viewModel::dismissCreatedInvite,
        )
    }
}

@Composable
private fun SeatRowItem(
    seat: SeatRow,
    position: ListItemPosition,
    busy: Boolean,
    onInvite: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val removed = seat.state == SeatState.REMOVED
    GroupedRow(position = position, modifier = modifier) {
        MemberAvatar(seat.name, dimmed = removed)
        RowLabels(
            title = if (seat.isMe) stringResource(R.string.shared_members_me, seat.name) else seat.name,
            subtitle = stringResource(
                when (seat.state) {
                    SeatState.JOINED -> R.string.shared_seat_joined
                    SeatState.NOT_JOINED -> R.string.shared_seat_not_joined
                    SeatState.REMOVED -> R.string.shared_seat_removed
                },
            ),
        )
        if (seat.state == SeatState.NOT_JOINED) {
            TextButton(onClick = onInvite, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_members_invite)) }
        }
        if (!removed) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shared_members_actions, seat.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_members_rename)) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    // Leaving (removing your own seat) is in group settings.
                    if (!seat.isMe) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shared_members_remove)) },
                            onClick = {
                                menu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteRowItem(invite: InviteRow, position: ListItemPosition, busy: Boolean, onRevoke: () -> Unit, modifier: Modifier = Modifier) {
    val expires = AppDates.dateFormatter().format(Instant.ofEpochMilli(invite.expiresAt).atZone(ZoneId.systemDefault()))
    GroupedRow(position = position, modifier = modifier) {
        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        RowLabels(
            title = when {
                !invite.forSeat -> stringResource(R.string.shared_invite_open)
                invite.seatName != null -> stringResource(R.string.shared_invite_for_seat, invite.seatName)
                else -> stringResource(R.string.shared_invite_for_unknown_seat)
            },
            subtitle = if (invite.forSeat) {
                stringResource(R.string.shared_invite_expires, expires)
            } else {
                pluralStringResource(R.plurals.shared_invite_expires_uses, invite.uses, expires, invite.uses)
            },
        )
        IconButton(onClick = onRevoke, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.shared_invite_revoke))
        }
    }
}

/** The new Invite link, with Share and Copy; the share sheet opened once already. */
@Composable
private fun CreatedInviteDialog(invite: CreatedInvite, onShare: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.shared_invite_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_invite_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    invite.seatName?.let { stringResource(R.string.shared_invite_link_body_seat, it) }
                        ?: stringResource(R.string.shared_invite_link_body_open),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(invite.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onShare, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_share)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        copyLink(context, invite.url)
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.shared_copy)) }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_done)) }
            }
        },
    )
}

private fun shareText(context: Context, invite: CreatedInvite, groupName: String, chooser: String) =
    shareLink(context, context.getString(R.string.shared_invite_share_text, groupName, invite.url), chooser)

private fun shareLink(context: Context, text: String, chooser: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.shared_invite_link_title), url))
}
