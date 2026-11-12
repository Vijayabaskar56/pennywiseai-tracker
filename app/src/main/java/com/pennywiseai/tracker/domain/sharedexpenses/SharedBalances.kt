package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.utils.Money
import com.pennywiseai.tracker.utils.sumByCurrency
import java.math.BigDecimal
import java.util.SortedMap

/** Balances keyed by currency, then member ID. Positive means the group owes that member. */
typealias GroupBalances = Map<String, Map<String, BigDecimal>>

/**
 * What one person owes and is owed across their groups, per currency. Each
 * group contributes its own net balance, so owing in one group and being owed
 * in another shows on both sides. Currencies are never added together.
 */
data class BalanceSummary(val owe: Map<String, Money>, val owed: Map<String, Money>)

/**
 * Group balances and who owes whom (docs/shared-expenses.md, "Balances,
 * simplified debts and settling up"). Everything is per currency: amounts of
 * different currencies are never added together. Deleted rows are ignored.
 */
object SharedBalances {

    /**
     * Each member's Group balance per currency: what they paid, minus their
     * Shares, plus Settlements they sent, minus Settlements they received.
     * Only members named by a row appear. Each currency's balances sum to zero.
     */
    fun balances(expenses: Iterable<SharedExpense>, settlements: Iterable<SharedSettlement>): GroupBalances {
        val entries = buildList {
            expenses.filter { it.deletedAt == null }.forEach { e ->
                e.payers.forEach { add(Entry(it.memberId, e.currency, it.amount)) }
                e.shares.forEach { add(Entry(it.memberId, e.currency, it.amount.negate())) }
            }
            settlements.filter { it.deletedAt == null }.forEach { s ->
                add(Entry(s.fromMemberId, s.currency, s.amount))
                add(Entry(s.toMemberId, s.currency, s.amount.negate()))
            }
        }
        val byMember = entries.groupBy { it.memberId }
            .mapValues { (_, rows) -> rows.sumByCurrency({ it.currency.uppercase() }, { it.amount }) }
        val result = sortedMapOf<String, SortedMap<String, BigDecimal>>()
        byMember.forEach { (memberId, perCurrency) ->
            perCurrency.forEach { (currency, money) ->
                result.getOrPut(currency) { sortedMapOf() }[memberId] = SharedMoney.normalize(money.amount, currency)
            }
        }
        return result
    }

    /** [memberId]'s balance in each currency of [balances]; currencies they aren't in are left out. */
    fun forMember(balances: GroupBalances, memberId: String): Map<String, BigDecimal> =
        balances.mapNotNull { (currency, members) -> members[memberId]?.let { currency to it } }.toMap()

    /**
     * Simplified debts for one [currency]: the member who owes the most pays
     * the member who is owed the most, repeatedly, ties going to the lower
     * member ID so every phone shows the same list. At most one fewer debt
     * than there are members with a balance.
     */
    fun simplifyDebts(balances: Map<String, BigDecimal>, currency: String): List<Debt> {
        val left = balances.filterValues { it.signum() != 0 }.toMutableMap()
        val debts = mutableListOf<Debt>()
        while (true) {
            val debtor = left.entries.filter { it.value.signum() < 0 }
                .minWithOrNull(compareBy<Map.Entry<String, BigDecimal>> { it.value }.thenBy { it.key }) ?: break
            val creditor = left.entries.filter { it.value.signum() > 0 }
                .minWithOrNull(compareByDescending<Map.Entry<String, BigDecimal>> { it.value }.thenBy { it.key }) ?: break
            val amount = debtor.value.negate().min(creditor.value)
            val from = debtor.key
            val to = creditor.key
            debts += Debt(from, to, SharedMoney.normalize(amount, currency), currency)
            left[from] = left.getValue(from) + amount
            left[to] = left.getValue(to) - amount
            if (left.getValue(from).signum() == 0) left.remove(from)
            if (left.getValue(to).signum() == 0) left.remove(to)
        }
        return debts
    }

    /** [simplifyDebts] for every currency in [balances], keyed by currency. */
    fun simplifiedDebts(balances: GroupBalances): Map<String, List<Debt>> =
        balances.mapValues { (currency, members) -> simplifyDebts(members, currency) }
            .filterValues { it.isNotEmpty() }
            .toSortedMap()

    /**
     * Debts per pair of members, for a group with simplified debts off. In
     * each expense, members who paid less than their Share pay those who paid
     * more, matched greedily in ascending member-ID order. A Settlement from A
     * to B reduces what A owes B (and past zero, B then owes A). Each pair is
     * netted to one direction; zero pairs are dropped. Keyed by currency, each
     * list sorted by debtor then creditor ID.
     */
    fun pairwiseDebts(expenses: Iterable<SharedExpense>, settlements: Iterable<SharedSettlement>): Map<String, List<Debt>> {
        // Key (currency, low ID, high ID); value is what low owes high, negative when high owes low.
        val owed = HashMap<Triple<String, String, String>, BigDecimal>()
        fun record(currency: String, from: String, to: String, amount: BigDecimal) {
            if (from == to) return
            val key = if (from < to) Triple(currency, from, to) else Triple(currency, to, from)
            val signed = if (from < to) amount else amount.negate()
            owed[key] = (owed[key] ?: BigDecimal.ZERO) + signed
        }

        expenses.filter { it.deletedAt == null }.forEach { e ->
            val currency = e.currency.uppercase()
            val net = HashMap<String, BigDecimal>()
            e.payers.forEach { net[it.memberId] = (net[it.memberId] ?: BigDecimal.ZERO) + it.amount }
            e.shares.forEach { net[it.memberId] = (net[it.memberId] ?: BigDecimal.ZERO) - it.amount }
            val debtors = net.filterValues { it.signum() < 0 }.keys.sorted()
            val creditors = net.filterValues { it.signum() > 0 }.keys.sorted()
            val owes = debtors.associateWithTo(HashMap()) { net.getValue(it).negate() }
            val due = creditors.associateWithTo(HashMap()) { net.getValue(it) }
            var d = 0
            var c = 0
            while (d < debtors.size && c < creditors.size) {
                val from = debtors[d]
                val to = creditors[c]
                val amount = owes.getValue(from).min(due.getValue(to))
                record(currency, from, to, amount)
                owes[from] = owes.getValue(from) - amount
                due[to] = due.getValue(to) - amount
                if (owes.getValue(from).signum() == 0) d++
                if (due.getValue(to).signum() == 0) c++
            }
        }
        // A Settlement from A to B is B taking on a debt to A.
        settlements.filter { it.deletedAt == null }.forEach { s ->
            record(s.currency.uppercase(), s.toMemberId, s.fromMemberId, s.amount)
        }

        return owed.entries
            .filter { it.value.signum() != 0 }
            .map { (key, value) ->
                val (currency, low, high) = key
                val amount = SharedMoney.normalize(value.abs(), currency)
                if (value.signum() > 0) Debt(low, high, amount, currency) else Debt(high, low, amount, currency)
            }
            .groupBy { it.currency }
            .mapValues { (_, debts) -> debts.sortedWith(compareBy({ it.fromMemberId }, { it.toMemberId })) }
            .toSortedMap()
    }

    /** Who owes whom in a group: [simplifiedDebts] when [simplify] is on, else [pairwiseDebts]. */
    fun debts(
        simplify: Boolean,
        expenses: Iterable<SharedExpense>,
        settlements: Iterable<SharedSettlement>,
    ): Map<String, List<Debt>> =
        if (simplify) simplifiedDebts(balances(expenses, settlements)) else pairwiseDebts(expenses, settlements)

    /**
     * The tab's summary line: given one person's balance per currency in each
     * of their groups (see [forMember]), what they owe and are owed in total,
     * per currency. Zero balances add nothing.
     */
    fun summarize(myGroupBalances: Iterable<Map<String, BigDecimal>>): BalanceSummary {
        val rows = myGroupBalances.flatMap { it.entries }
        val owe = rows.filter { it.value.signum() < 0 }.sumByCurrency({ it.key.uppercase() }, { it.value.negate() })
        val owed = rows.filter { it.value.signum() > 0 }.sumByCurrency({ it.key.uppercase() }, { it.value })
        return BalanceSummary(owe.toSortedMap(), owed.toSortedMap())
    }

    private data class Entry(val memberId: String, val currency: String, val amount: BigDecimal)
}
