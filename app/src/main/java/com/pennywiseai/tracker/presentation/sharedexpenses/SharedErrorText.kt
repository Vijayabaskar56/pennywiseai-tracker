package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.annotation.StringRes
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkRefusal
import com.pennywiseai.tracker.data.sharedexpenses.SharedServerCode
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.ui.UiText
import java.net.URI

// The one place a Shared expenses error becomes words. Screens never show a raw
// exception or the server's own message: every SharedError maps to a string here.

/** What to tell the user about [error]. */
fun sharedErrorText(error: SharedError): UiText = when (error) {
    is SharedError.Invalid -> UiText.Res(invalidReasonRes(error.reason))
    SharedError.NoServerAddress -> UiText.Res(R.string.shared_error_no_server)
    SharedError.InvalidServerAddress -> UiText.Res(R.string.shared_error_invalid_server)
    is SharedError.OtherServer -> UiText.Res(R.string.shared_error_other_server, listOf(serverHost(error.inviteServer)))
    SharedError.DisplayNameRequired -> UiText.Res(R.string.shared_error_name_required)
    SharedError.NotFound -> UiText.Res(R.string.shared_error_not_on_phone)
    SharedError.DeletedElsewhere -> UiText.Res(R.string.shared_error_deleted_elsewhere)
    is SharedError.LinkRefused -> UiText.Res(linkRefusalRes(error.reason))
    is SharedError.Network -> UiText.Res(R.string.shared_error_network)
    is SharedError.Server -> UiText.Res(serverCodeRes(error.code))
}

/** Why a bank transaction could not be linked. */
@StringRes
fun linkRefusalRes(reason: SharedLinkRefusal): Int = when (reason) {
    SharedLinkRefusal.TRANSACTION_GONE -> R.string.shared_link_refused_gone
    SharedLinkRefusal.NOT_A_DEBIT -> R.string.shared_link_refused_not_debit
    SharedLinkRefusal.NOT_A_CREDIT -> R.string.shared_link_refused_not_credit
    SharedLinkRefusal.CURRENCY_MISMATCH -> R.string.shared_link_refused_currency
    SharedLinkRefusal.TRANSACTION_TAKEN -> R.string.shared_link_refused_taken
    SharedLinkRefusal.SPLIT_OR_LOAN -> R.string.shared_link_refused_split
    SharedLinkRefusal.APP_ENTRY -> R.string.shared_link_refused_entry
    SharedLinkRefusal.NOT_A_PAYER -> R.string.shared_link_refused_not_payer
    SharedLinkRefusal.NOT_A_PARTY -> R.string.shared_link_refused_not_party
    SharedLinkRefusal.ALREADY_LINKED -> R.string.shared_link_refused_already
}

/** The message for a server `error` code. */
@StringRes
fun serverCodeRes(code: SharedServerCode): Int = when (code) {
    SharedServerCode.UNAUTHORIZED -> R.string.shared_error_unauthorized
    SharedServerCode.FORBIDDEN -> R.string.shared_error_forbidden
    SharedServerCode.NOT_FOUND -> R.string.shared_error_not_found
    SharedServerCode.CONFLICT -> R.string.shared_error_conflict
    SharedServerCode.ALREADY_MEMBER -> R.string.shared_error_already_member
    SharedServerCode.GONE -> R.string.shared_error_invite_gone
    SharedServerCode.INVALID -> R.string.shared_error_invalid
    SharedServerCode.BALANCE_NOT_ZERO -> R.string.shared_error_balance_not_zero
    SharedServerCode.LIMIT_REACHED -> R.string.shared_error_limit
    SharedServerCode.RATE_LIMITED -> R.string.shared_error_rate_limited
    SharedServerCode.BUSY, SharedServerCode.INTERNAL -> R.string.shared_error_server_problem
    SharedServerCode.UNKNOWN -> R.string.shared_error_unknown
}

/** The message for an input the phone refused before sending it. */
@StringRes
fun invalidReasonRes(reason: SharedExpenseError): Int = when (reason) {
    SharedExpenseError.DESCRIPTION_BLANK -> R.string.shared_invalid_description_blank
    SharedExpenseError.DESCRIPTION_TOO_LONG -> R.string.shared_invalid_description_long
    SharedExpenseError.CATEGORY_TOO_LONG -> R.string.shared_invalid_category_long
    SharedExpenseError.NOTES_TOO_LONG -> R.string.shared_invalid_notes_long
    SharedExpenseError.INVALID_CURRENCY -> R.string.shared_invalid_currency
    SharedExpenseError.AMOUNT_NOT_POSITIVE -> R.string.shared_invalid_amount_positive
    SharedExpenseError.AMOUNT_TOO_MANY_DECIMALS -> R.string.shared_invalid_amount_decimals
    SharedExpenseError.AMOUNT_TOO_LARGE -> R.string.shared_invalid_amount_large
    SharedExpenseError.NO_PAYERS -> R.string.shared_invalid_no_payers
    SharedExpenseError.PAYER_NOT_POSITIVE -> R.string.shared_invalid_payer_positive
    SharedExpenseError.PAYER_TOO_MANY_DECIMALS -> R.string.shared_invalid_payer_decimals
    SharedExpenseError.DUPLICATE_PAYER -> R.string.shared_invalid_payer_duplicate
    SharedExpenseError.PAYERS_NOT_EQUAL_AMOUNT -> R.string.shared_invalid_payers_sum
    SharedExpenseError.NO_SHARES -> R.string.shared_invalid_no_shares
    SharedExpenseError.SHARE_NOT_POSITIVE -> R.string.shared_invalid_share_positive
    SharedExpenseError.SHARE_TOO_MANY_DECIMALS -> R.string.shared_invalid_share_decimals
    SharedExpenseError.DUPLICATE_SHARE -> R.string.shared_invalid_share_duplicate
    SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT -> R.string.shared_invalid_shares_sum
    SharedExpenseError.PERCENT_INVALID -> R.string.shared_invalid_percent
    SharedExpenseError.PERCENT_NOT_100 -> R.string.shared_invalid_percent_total
    SharedExpenseError.SHARE_WEIGHT_INVALID -> R.string.shared_invalid_share_weight
    SharedExpenseError.WEIGHT_NOT_ALLOWED -> R.string.shared_invalid_weight_not_allowed
    SharedExpenseError.UNKNOWN_MEMBER -> R.string.shared_invalid_unknown_member
    SharedExpenseError.SETTLEMENT_SAME_MEMBER -> R.string.shared_invalid_settlement_same
    SharedExpenseError.GROUP_NAME_BLANK -> R.string.shared_invalid_group_name_blank
    SharedExpenseError.GROUP_NAME_TOO_LONG -> R.string.shared_invalid_group_name_long
    SharedExpenseError.DISPLAY_NAME_BLANK -> R.string.shared_invalid_name_blank
    SharedExpenseError.DISPLAY_NAME_TOO_LONG -> R.string.shared_invalid_name_long
}

/** True when [error] means "no connection", which screens show as being offline rather than as a failure. */
val SharedError.isOffline: Boolean get() = this is SharedError.Network

/** The host (and port) of a server address, for messages; the address itself when it can't be read. */
fun serverHost(address: String): String =
    runCatching { URI(address) }.getOrNull()?.let { uri ->
        uri.host?.let { host -> if (uri.port == -1) host else "$host:${uri.port}" }
    } ?: address
