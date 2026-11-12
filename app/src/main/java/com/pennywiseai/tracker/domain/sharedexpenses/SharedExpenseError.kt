package com.pennywiseai.tracker.domain.sharedexpenses

import java.math.BigInteger

/**
 * Why a Shared expense, Settlement, split or name can't be saved. [field] is
 * the input it is about, matching the `field` of the server's `422 invalid`.
 */
enum class SharedExpenseError(val field: String) {
    DESCRIPTION_BLANK("description"),
    DESCRIPTION_TOO_LONG("description"),
    CATEGORY_TOO_LONG("category"),
    NOTES_TOO_LONG("notes"),
    INVALID_CURRENCY("currency"),
    AMOUNT_NOT_POSITIVE("amount"),
    AMOUNT_TOO_MANY_DECIMALS("amount"),
    AMOUNT_TOO_LARGE("amount"),
    NO_PAYERS("payers"),
    PAYER_NOT_POSITIVE("payers"),
    PAYER_TOO_MANY_DECIMALS("payers"),
    DUPLICATE_PAYER("payers"),
    PAYERS_NOT_EQUAL_AMOUNT("payers"),
    NO_SHARES("shares"),
    SHARE_NOT_POSITIVE("shares"),
    SHARE_TOO_MANY_DECIMALS("shares"),
    DUPLICATE_SHARE("shares"),
    SHARES_NOT_EQUAL_AMOUNT("shares"),

    /** A PERCENT weight that isn't a number above zero. */
    PERCENT_INVALID("shares"),
    PERCENT_NOT_100("shares"),

    /** A SHARES weight that isn't a whole number above zero. */
    SHARE_WEIGHT_INVALID("shares"),

    /** A weight on an EQUAL or EXACT share, which the server refuses. */
    WEIGHT_NOT_ALLOWED("shares"),

    /** A payer, share or settlement member that isn't in the group. */
    UNKNOWN_MEMBER("memberId"),
    SETTLEMENT_SAME_MEMBER("toMemberId"),
    GROUP_NAME_BLANK("name"),
    GROUP_NAME_TOO_LONG("name"),
    DISPLAY_NAME_BLANK("displayName"),
    DISPLAY_NAME_TOO_LONG("displayName"),
}

/** The server's limits (docs/shared-expenses-api.md, "Limits"). Lengths count Unicode code points, as the server does. */
object SharedLimits {
    const val MAX_MEMBERS_PER_GROUP = 50
    const val MAX_GROUPS_PER_USER = 50
    const val MAX_GROUP_NAME = 60
    const val MAX_DISPLAY_NAME = 40
    const val MAX_DESCRIPTION = 200
    const val MAX_NOTES = 1000
    const val MAX_CATEGORY = 60

    /** An amount must be below this many minor units (10^12). */
    val MAX_MINOR_UNITS_EXCLUSIVE: BigInteger = BigInteger.TEN.pow(12)
}
