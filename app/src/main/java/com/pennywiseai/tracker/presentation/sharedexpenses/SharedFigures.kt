package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.data.sharedexpenses.SharedInviteInfo
import com.pennywiseai.tracker.data.sharedexpenses.SharedOutboxProblem
import com.pennywiseai.tracker.domain.sharedexpenses.BalanceSummary
import com.pennywiseai.tracker.domain.sharedexpenses.Debt
import com.pennywiseai.tracker.domain.sharedexpenses.SharedBalances
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

// Pure figures behind the Shared screens: no Android, no Compose. Every amount
// stays with its own currency; nothing here adds two currencies together.

/** Whether the group owes me ([OWED]) or I owe the group ([OWE]). */
enum class BalanceDirection { OWED, OWE }

/** One currency of my balance: [amount] is never negative, [direction] says which way. */
data class BalanceLine(val amount: BigDecimal, val currency: String, val direction: BalanceDirection)

/** My balance per currency as lines, by currency code; zero balances are left out. An empty list means settled up. */
fun balanceLines(myBalances: Map<String, BigDecimal>): List<BalanceLine> =
    myBalances.entries
        .filter { it.value.signum() != 0 }
        .sortedBy { it.key }
        .map { (currency, amount) ->
            BalanceLine(amount.abs(), currency, if (amount.signum() > 0) BalanceDirection.OWED else BalanceDirection.OWE)
        }

/** One Shared group as its card on the tab shows it. */
data class SharedGroupCard(
    val id: String,
    val name: String,
    val defaultCurrency: String,
    /** Active seats (removed members are not counted). */
    val memberCount: Int,
    /** My balance per currency in this group; feeds [summarizeGroups]. */
    val myBalances: Map<String, BigDecimal>,
) {
    val lines: List<BalanceLine> get() = balanceLines(myBalances)
}

/** [group]'s card: active member count and my balance per currency (empty when this phone's seat is unknown). */
fun groupCard(
    group: SharedGroup,
    members: List<SharedMember>,
    expenses: List<SharedExpense>,
    settlements: List<SharedSettlement>,
): SharedGroupCard {
    val me = group.myMemberId
    val mine = if (me == null) emptyMap() else SharedBalances.forMember(SharedBalances.balances(expenses, settlements), me)
    return SharedGroupCard(
        id = group.id,
        name = group.name,
        defaultCurrency = group.defaultCurrency,
        memberCount = members.count { it.groupId == group.id && it.removedAt == null },
        myBalances = mine,
    )
}

/** What I owe and am owed across [cards], per currency. */
fun summarizeGroups(cards: List<SharedGroupCard>): BalanceSummary = SharedBalances.summarize(cards.map { it.myBalances })

/** A member as a row names them: this phone's own seat reads "You". */
sealed interface PersonRef {
    data object Me : PersonRef

    data class Named(val name: String) : PersonRef

    /** A member ID this phone has no seat for (should not happen once synced). */
    data object Unknown : PersonRef
}

fun personRef(memberId: String, myMemberId: String?, names: Map<String, String>): PersonRef = when {
    memberId == myMemberId -> PersonRef.Me
    else -> names[memberId]?.let { PersonRef.Named(it) } ?: PersonRef.Unknown
}

/** Who paid an expense: "You paid", "Member A paid", "2 people paid". */
sealed interface PayerSummary {
    data class One(val who: PersonRef) : PayerSummary

    data class Several(val count: Int) : PayerSummary
}

/** My part of an expense, in its currency. */
sealed interface MyPart {
    /** I paid more than my Share: others owe me [amount] for it. */
    data class Lent(val amount: BigDecimal, val currency: String) : MyPart

    /** I paid less than my Share: I owe [amount] for it. */
    data class Borrowed(val amount: BigDecimal, val currency: String) : MyPart

    /** I paid exactly my Share. */
    data object Even : MyPart

    /** Neither a Payer nor holding a Share. */
    data object NotInvolved : MyPart
}

/** A row's sync state: saved here only ([PENDING]), or with a change the server refused or that conflicts ([PROBLEM]). */
enum class SyncMark { NONE, PENDING, PROBLEM }

/** The group's ledger: month headers, then expenses and Settlements in that month, newest first. */
sealed interface LedgerItem {
    val key: String

    data class Month(val month: YearMonth) : LedgerItem {
        override val key get() = "month-$month"
    }

    data class Expense(
        val id: String,
        val description: String,
        val date: LocalDate,
        val amount: BigDecimal,
        val currency: String,
        val payer: PayerSummary,
        val myPart: MyPart,
        val mark: SyncMark,
    ) : LedgerItem {
        override val key get() = "expense-$id"
    }

    data class Settlement(
        val id: String,
        val from: PersonRef,
        val to: PersonRef,
        val date: LocalDate,
        val amount: BigDecimal,
        val currency: String,
        val mark: SyncMark,
    ) : LedgerItem {
        override val key get() = "settlement-$id"
    }
}

fun payerSummary(expense: SharedExpense, myMemberId: String?, names: Map<String, String>): PayerSummary {
    val payers = expense.payers.map { it.memberId }.distinct()
    return if (payers.size == 1) PayerSummary.One(personRef(payers.single(), myMemberId, names)) else PayerSummary.Several(payers.size)
}

fun myPart(expense: SharedExpense, myMemberId: String?): MyPart {
    if (myMemberId == null) return MyPart.NotInvolved
    val paid = expense.payers.filter { it.memberId == myMemberId }
    val share = expense.shares.filter { it.memberId == myMemberId }
    if (paid.isEmpty() && share.isEmpty()) return MyPart.NotInvolved
    val net = paid.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount } - share.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount }
    return when (net.signum()) {
        1 -> MyPart.Lent(net, expense.currency)
        -1 -> MyPart.Borrowed(net.negate(), expense.currency)
        else -> MyPart.Even
    }
}

/** IDs of rows with a change the server refused or that clashes with someone else's edit. */
fun problemIds(problems: List<SharedOutboxProblem>, groupId: String? = null): Set<String> =
    problems.filter { groupId == null || it.groupId == groupId }.map { it.entityId }.toSet()

private fun mark(id: String, pending: Boolean, problems: Set<String>): SyncMark = when {
    id in problems -> SyncMark.PROBLEM
    pending -> SyncMark.PENDING
    else -> SyncMark.NONE
}

/**
 * Expenses and Settlements together, newest date first (then newest created),
 * with a [LedgerItem.Month] before each month's rows. Deleted rows are left out.
 */
fun buildLedger(
    expenses: List<SharedExpense>,
    settlements: List<SharedSettlement>,
    myMemberId: String?,
    names: Map<String, String>,
    problems: Set<String> = emptySet(),
): List<LedgerItem> {
    data class Dated(val date: LocalDate, val createdAt: Long, val item: LedgerItem)

    val rows = expenses.filter { it.deletedAt == null }.map { e ->
        Dated(
            e.date, e.createdAt,
            LedgerItem.Expense(
                id = e.id, description = e.description, date = e.date, amount = e.amount, currency = e.currency,
                payer = payerSummary(e, myMemberId, names), myPart = myPart(e, myMemberId), mark = mark(e.id, e.pending, problems),
            ),
        )
    } + settlements.filter { it.deletedAt == null }.map { s ->
        Dated(
            s.date, s.createdAt,
            LedgerItem.Settlement(
                id = s.id, from = personRef(s.fromMemberId, myMemberId, names), to = personRef(s.toMemberId, myMemberId, names),
                date = s.date, amount = s.amount, currency = s.currency, mark = mark(s.id, s.pending, problems),
            ),
        )
    }
    val sorted = rows.sortedWith(compareByDescending<Dated> { it.date }.thenByDescending { it.createdAt }.thenBy { it.item.key })
    return buildList {
        var month: YearMonth? = null
        sorted.forEach { row ->
            val m = YearMonth.from(row.date)
            if (m != month) {
                add(LedgerItem.Month(m))
                month = m
            }
            add(row.item)
        }
    }
}

/** One "who owes whom" line; tapping it settles up [fromId] → [toId] for [amount] in [currency]. */
data class DebtLine(
    val fromId: String,
    val toId: String,
    val from: PersonRef,
    val to: PersonRef,
    val amount: BigDecimal,
    val currency: String,
) {
    val key: String get() = "$currency-$fromId-$toId"
}

/** [debts] (by currency) as lines with names: the ones that involve me first, then by currency. */
fun debtLines(debts: Map<String, List<Debt>>, myMemberId: String?, names: Map<String, String>): List<DebtLine> =
    debts.toSortedMap().values.flatten()
        .map { DebtLine(it.fromMemberId, it.toMemberId, personRef(it.fromMemberId, myMemberId, names), personRef(it.toMemberId, myMemberId, names), it.amount, it.currency) }
        .sortedBy { if (it.from == PersonRef.Me || it.to == PersonRef.Me) 0 else 1 }

/** A seat's state on the Members screen. */
enum class SeatState { JOINED, NOT_JOINED, REMOVED }

fun seatState(member: SharedMember): SeatState = when {
    member.removedAt != null -> SeatState.REMOVED
    member.joined -> SeatState.JOINED
    else -> SeatState.NOT_JOINED
}

/** A seat as the Members screen lists it. */
data class SeatRow(val id: String, val name: String, val state: SeatState, val isMe: Boolean)

/** Active seats first (me first, then by name), removed seats last. */
fun seatRows(members: List<SharedMember>, myMemberId: String?): List<SeatRow> =
    members.map { SeatRow(it.id, it.displayName, seatState(it), it.id == myMemberId) }
        .sortedWith(compareBy<SeatRow> { it.state == SeatState.REMOVED }.thenBy { !it.isMe }.thenBy { it.name.lowercase() }.thenBy { it.id })

/**
 * An active Invite link as the Members screen lists it. [forSeat] is false for
 * an open invite; [seatName] is the seat's name, null when that seat is not on
 * this phone.
 */
data class InviteRow(val id: String, val forSeat: Boolean, val seatName: String?, val expiresAt: Long, val uses: Int)

/** Soonest expiry first. */
fun inviteRows(invites: List<SharedInviteInfo>, names: Map<String, String>): List<InviteRow> =
    invites.sortedWith(compareBy<SharedInviteInfo> { it.expiresAt }.thenBy { it.id })
        .map { InviteRow(it.id, it.memberId != null, it.memberId?.let(names::get), it.expiresAt, it.uses) }
