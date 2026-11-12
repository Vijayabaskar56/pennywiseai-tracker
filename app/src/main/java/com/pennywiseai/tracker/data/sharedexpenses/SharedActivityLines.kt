package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.core.ResText
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.utils.CurrencyFormatter

/**
 * Text a person typed (a member's name, a description), kept apart from the
 * app's own wording so each place that shows it can treat it as such.
 */
data class TypedText(val value: String)

/**
 * The wording of the Activity feed, shared by the feed screen and the
 * notification. A line is a [ResText] whose args are plain strings, nested
 * [ResText] or [TypedText].
 */
object SharedActivityLines {
    /** Who did it: "You", a member's name, or "Recurring" when the server acted (a recurring expense came due). */
    fun actor(actorId: String?, myMemberId: String?, names: Map<String, String>): Any = when {
        actorId == null -> ResText(R.string.shared_actor_recurring)
        actorId == myMemberId -> ResText(R.string.shared_person_you)
        else -> names[actorId]?.let { TypedText(it) } ?: ResText(R.string.shared_person_unknown)
    }

    /** A member mid-sentence: "you", their name now, else [fallback] (the name the row recorded). */
    private fun member(memberId: String?, myMemberId: String?, names: Map<String, String>, fallback: String? = null): Any = when {
        memberId != null && memberId == myMemberId -> ResText(R.string.shared_person_you_lower)
        else -> (fallback ?: memberId?.let(names::get))?.let { TypedText(it) } ?: ResText(R.string.shared_person_unknown)
    }

    /** The summary's amount in its currency ("₹1,240.00"), or null when it has none. */
    private fun summaryMoney(summary: Map<String, String>, maskIfHidden: Boolean): String? {
        val amount = summary["amount"]?.toBigDecimalOrNull() ?: return null
        val currency = summary["currency"]?.takeIf { it.isNotBlank() } ?: return null
        return CurrencyFormatter.formatCurrency(amount, currency, maskIfHidden = maskIfHidden)
    }

    /** "Groceries, ₹1,240.00", or just the description when the amount is missing. */
    private fun subject(summary: Map<String, String>, maskIfHidden: Boolean): Any {
        val description = summary["description"]?.let { TypedText(it) } ?: ResText(R.string.shared_activity_an_expense)
        val money = summaryMoney(summary, maskIfHidden) ?: return description
        return ResText(R.string.shared_activity_subject_amount, listOf(description, money))
    }

    private fun interval(summary: Map<String, String>): ResText =
        when (summary["interval"]?.let { raw -> RecurringInterval.entries.firstOrNull { it.name == raw } }) {
            RecurringInterval.WEEKLY -> ResText(R.string.shared_activity_interval_weekly)
            RecurringInterval.MONTHLY -> ResText(R.string.shared_activity_interval_monthly)
            RecurringInterval.YEARLY -> ResText(R.string.shared_activity_interval_yearly)
            null -> ResText(R.string.shared_activity_interval_unknown)
        }

    /**
     * One feed line from [activity]'s type and summary, such as "Member A added
     * Groceries, ₹1,240.00". The actor reads "You" for this phone's seat and
     * "Recurring" when the server acted. Every [ActivityType] has its line.
     * Text that leaves the screen (a notification) passes [maskIfHidden] = false.
     */
    fun line(activity: SharedActivity, myMemberId: String?, names: Map<String, String>, maskIfHidden: Boolean = true): ResText {
        val s = activity.summary
        val actor = actor(activity.actorMemberId, myMemberId, names)
        fun res(id: Int, vararg args: Any) = ResText(id, listOf(actor) + args)
        return when (activity.type) {
            ActivityType.GROUP_CREATED -> res(R.string.shared_activity_group_created, s["name"].orEmpty())
            ActivityType.GROUP_DELETED -> res(R.string.shared_activity_group_deleted, s["name"].orEmpty())
            ActivityType.GROUP_RENAMED -> s["oldName"]?.let { old -> res(R.string.shared_activity_group_renamed, old, s["name"].orEmpty()) }
                ?: res(R.string.shared_activity_group_renamed_to, s["name"].orEmpty())
            ActivityType.GROUP_SETTINGS_CHANGED -> {
                val currency = s["defaultCurrency"]
                val simplify = s["simplifyDebts"]?.toBooleanStrictOrNull()
                when {
                    currency != null && simplify == null -> res(R.string.shared_activity_settings_currency, currency)
                    currency == null && simplify == true -> res(R.string.shared_activity_settings_simplify_on)
                    currency == null && simplify == false -> res(R.string.shared_activity_settings_simplify_off)
                    else -> res(R.string.shared_activity_settings_changed)
                }
            }
            ActivityType.MEMBER_ADDED -> res(R.string.shared_activity_member_added, member(activity.entityId, myMemberId, names, s["memberName"]))
            ActivityType.MEMBER_JOINED -> {
                // The joiner is the actor; before this phone knows their seat, the recorded name stands in.
                val joiner = if (activity.actorMemberId == myMemberId || activity.actorMemberId in names) actor else s["memberName"]?.let { TypedText(it) } ?: actor
                ResText(R.string.shared_activity_member_joined, listOf(joiner))
            }
            ActivityType.MEMBER_REMOVED -> if (activity.actorMemberId != null && activity.actorMemberId == activity.entityId) {
                res(R.string.shared_activity_member_left)
            } else {
                res(R.string.shared_activity_member_removed, member(activity.entityId, myMemberId, names, s["memberName"]))
            }
            ActivityType.MEMBER_RENAMED -> s["oldName"]?.let { old -> res(R.string.shared_activity_member_renamed, old, s["memberName"].orEmpty()) }
                ?: res(R.string.shared_activity_member_renamed_to, s["memberName"].orEmpty())
            ActivityType.EXPENSE_ADDED -> res(R.string.shared_activity_expense_added, subject(s, maskIfHidden))
            ActivityType.EXPENSE_EDITED -> res(R.string.shared_activity_expense_edited, subject(s, maskIfHidden))
            ActivityType.EXPENSE_DELETED -> res(R.string.shared_activity_expense_deleted, subject(s, maskIfHidden))
            ActivityType.EXPENSE_RESTORED -> res(R.string.shared_activity_expense_restored, subject(s, maskIfHidden))
            ActivityType.SETTLEMENT_ADDED, ActivityType.SETTLEMENT_EDITED, ActivityType.SETTLEMENT_DELETED -> {
                val id = when (activity.type) {
                    ActivityType.SETTLEMENT_ADDED -> R.string.shared_activity_settlement_added
                    ActivityType.SETTLEMENT_EDITED -> R.string.shared_activity_settlement_edited
                    else -> R.string.shared_activity_settlement_deleted
                }
                val money = summaryMoney(s, maskIfHidden) ?: listOfNotNull(s["amount"], s["currency"]).joinToString(" ")
                res(id, member(s["fromMemberId"], myMemberId, names), member(s["toMemberId"], myMemberId, names), money)
            }
            ActivityType.RECURRING_ADDED -> res(R.string.shared_activity_recurring_added, subject(s, maskIfHidden), interval(s))
            ActivityType.RECURRING_EDITED -> res(R.string.shared_activity_recurring_edited, subject(s, maskIfHidden), interval(s))
            ActivityType.RECURRING_STOPPED -> res(R.string.shared_activity_recurring_stopped, subject(s, maskIfHidden), interval(s))
        }
    }
}
