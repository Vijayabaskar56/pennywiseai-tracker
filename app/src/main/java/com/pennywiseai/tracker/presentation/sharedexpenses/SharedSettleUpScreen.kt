package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R

/** Record a Settlement, optionally prefilled with the pair, [amount] (a decimal string) and [currency]. Placeholder until the form is written. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedSettleUpScreen(
    groupId: String,
    fromMemberId: String?,
    toMemberId: String?,
    amount: String?,
    currency: String?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    SharedPlaceholderScreen(title = stringResource(R.string.shared_settle_up_title), onNavigateBack = onNavigateBack)
}
