package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedInviteInfo
import com.pennywiseai.tracker.data.sharedexpenses.SharedOperation
import com.pennywiseai.tracker.data.sharedexpenses.SharedOutboxProblem
import com.pennywiseai.tracker.domain.sharedexpenses.Debt
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** The Shared screens' pure figures. Generic names only ("Member A", "Flat"). */
class SharedFiguresTest {

    private val me = "m-me"
    private val a = "m-a"
    private val b = "m-b"
    private val names = mapOf(me to "Me", a to "Member A", b to "Member B")

    private fun d(v: String) = BigDecimal(v)

    private fun expense(
        id: String,
        payers: List<Pair<String, String>>,
        shares: List<Pair<String, String>>,
        currency: String = "INR",
        date: LocalDate = LocalDate.of(2026, 11, 10),
        createdAt: Long = 0,
        pending: Boolean = false,
        deletedAt: Long? = null,
    ) = SharedExpense(
        id = id, groupId = "g", description = "Groceries", amount = payers.fold(BigDecimal.ZERO) { acc, p -> acc + d(p.second) },
        currency = currency, date = date, splitMethod = SplitMethod.EXACT,
        payers = payers.map { PayerPart(it.first, d(it.second)) }, shares = shares.map { SharePart(it.first, d(it.second)) },
        createdBy = me, updatedBy = me, createdAt = createdAt, updatedAt = createdAt, deletedAt = deletedAt, version = 1, rev = 1, pending = pending,
    )

    private fun settlement(id: String, from: String, to: String, amount: String, date: LocalDate, currency: String = "INR", pending: Boolean = false) =
        SharedSettlement(
            id = id, groupId = "g", fromMemberId = from, toMemberId = to, amount = d(amount), currency = currency, date = date,
            createdBy = from, updatedBy = from, createdAt = 0, updatedAt = 0, version = 1, rev = 1, pending = pending,
        )

    private fun member(id: String, name: String, joined: Boolean = true, removedAt: Long? = null) =
        SharedMember(id = id, groupId = "g", displayName = name, joined = joined, removedAt = removedAt, rev = 1)

    private val group = SharedGroup(id = "g", name = "Flat", defaultCurrency = "INR", createdAt = 0, updatedAt = 0, rev = 1, myMemberId = me)

    // ── Balance lines ──

    @Test fun `balance lines keep each currency apart and drop zeros`() {
        val lines = balanceLines(mapOf("USD" to d("-12.50"), "INR" to d("500.00"), "EUR" to d("0.00")))
        assertEquals(
            listOf(
                BalanceLine(d("500.00"), "INR", BalanceDirection.OWED),
                BalanceLine(d("12.50"), "USD", BalanceDirection.OWE),
            ),
            lines,
        )
    }

    @Test fun `no balance reads as settled up`() {
        assertTrue(balanceLines(emptyMap()).isEmpty())
        assertTrue(balanceLines(mapOf("INR" to d("0"))).isEmpty())
    }

    @Test fun `group card counts active members and my balance per currency`() {
        val card = groupCard(
            group,
            members = listOf(member(me, "Me"), member(a, "Member A"), member(b, "Member B", removedAt = 5)),
            expenses = listOf(
                expense("e1", payers = listOf(me to "1000"), shares = listOf(me to "500", a to "500")),
                expense("e2", payers = listOf(a to "30"), shares = listOf(me to "10", a to "20"), currency = "USD"),
            ),
            settlements = emptyList(),
        )
        assertEquals(2, card.memberCount)
        assertEquals(
            listOf(Triple("500", "INR", BalanceDirection.OWED), Triple("10", "USD", BalanceDirection.OWE)),
            card.lines.map { Triple(it.amount.stripTrailingZeros().toPlainString(), it.currency, it.direction) },
        )
    }

    @Test fun `summary across groups never adds currencies`() {
        val flat = groupCard(group, emptyList(), listOf(expense("e1", listOf(me to "100"), listOf(a to "100"))), emptyList())
        val trip = groupCard(group.copy(id = "t"), emptyList(), listOf(expense("e2", listOf(a to "40"), listOf(me to "40"), currency = "USD")), emptyList())
        val summary = summarizeGroups(listOf(flat, trip))
        assertEquals(setOf("INR"), summary.owed.keys)
        assertEquals(setOf("USD"), summary.owe.keys)
        assertEquals(0, d("100").compareTo(summary.owed.getValue("INR").amount))
        assertEquals(0, d("40").compareTo(summary.owe.getValue("USD").amount))
    }

    @Test fun `a phone without a known seat shows no balance`() {
        val card = groupCard(group.copy(myMemberId = null), emptyList(), listOf(expense("e1", listOf(a to "100"), listOf(b to "100"))), emptyList())
        assertTrue(card.lines.isEmpty())
    }

    // ── Row text ──

    @Test fun `payer summary names me, one member or a count`() {
        assertEquals(PayerSummary.One(PersonRef.Me), payerSummary(expense("e", listOf(me to "10"), listOf(a to "10")), me, names))
        assertEquals(PayerSummary.One(PersonRef.Named("Member A")), payerSummary(expense("e", listOf(a to "10"), listOf(me to "10")), me, names))
        assertEquals(PayerSummary.Several(2), payerSummary(expense("e", listOf(a to "5", b to "5"), listOf(me to "10")), me, names))
        assertEquals(PayerSummary.One(PersonRef.Unknown), payerSummary(expense("e", listOf("m-gone" to "10"), listOf(me to "10")), me, names))
    }

    @Test fun `my part is lent, borrowed, even or not involved`() {
        assertEquals(MyPart.Lent(d("500"), "INR"), myPart(expense("e", listOf(me to "1000"), listOf(me to "500", a to "500")), me))
        assertEquals(MyPart.Borrowed(d("300"), "INR"), myPart(expense("e", listOf(a to "900"), listOf(me to "300", a to "600")), me))
        assertEquals(MyPart.Even, myPart(expense("e", listOf(me to "300", a to "300"), listOf(me to "300", a to "300")), me))
        assertEquals(MyPart.NotInvolved, myPart(expense("e", listOf(a to "100"), listOf(b to "100")), me))
        assertEquals(MyPart.NotInvolved, myPart(expense("e", listOf(me to "100"), listOf(a to "100")), null))
    }

    // ── Ledger ──

    @Test fun `ledger mixes expenses and settlements newest first under month headers`() {
        val ledger = buildLedger(
            expenses = listOf(
                expense("old", listOf(me to "10"), listOf(a to "10"), date = LocalDate.of(2026, 10, 3)),
                expense("new", listOf(a to "20"), listOf(me to "20"), date = LocalDate.of(2026, 11, 20), createdAt = 1),
                expense("newer-same-day", listOf(a to "20"), listOf(me to "20"), date = LocalDate.of(2026, 11, 20), createdAt = 2),
                expense("deleted", listOf(a to "20"), listOf(me to "20"), deletedAt = 9),
            ),
            settlements = listOf(settlement("s1", a, me, "5", LocalDate.of(2026, 11, 5))),
            myMemberId = me,
            names = names,
        )
        assertEquals(
            listOf("month-2026-11", "expense-newer-same-day", "expense-new", "settlement-s1", "month-2026-10", "expense-old"),
            ledger.map { it.key },
        )
        assertEquals(LedgerItem.Month(YearMonth.of(2026, 11)), ledger.first())
        val s = ledger.filterIsInstance<LedgerItem.Settlement>().single()
        assertEquals(PersonRef.Named("Member A"), s.from)
        assertEquals(PersonRef.Me, s.to)
    }

    @Test fun `ledger keys are unique even when an expense and a settlement share an id`() {
        val ledger = buildLedger(
            listOf(expense("x", listOf(me to "10"), listOf(a to "10"))),
            listOf(settlement("x", a, me, "5", LocalDate.of(2026, 11, 10))),
            me, names,
        )
        assertEquals(ledger.size, ledger.map { it.key }.toSet().size)
    }

    @Test fun `rows marked pending or with a problem`() {
        val ledger = buildLedger(
            listOf(
                expense("sent", listOf(me to "10"), listOf(a to "10")),
                expense("queued", listOf(me to "10"), listOf(a to "10"), pending = true),
                expense("refused", listOf(me to "10"), listOf(a to "10"), pending = true),
            ),
            listOf(settlement("s", me, a, "5", LocalDate.of(2026, 11, 10), pending = true)),
            me, names, problems = setOf("refused"),
        )
        val marks = ledger.associate { it.key to ((it as? LedgerItem.Expense)?.mark ?: (it as? LedgerItem.Settlement)?.mark) }
        assertEquals(SyncMark.NONE, marks["expense-sent"])
        assertEquals(SyncMark.PENDING, marks["expense-queued"])
        assertEquals(SyncMark.PROBLEM, marks["expense-refused"])
        assertEquals(SyncMark.PENDING, marks["settlement-s"])
    }

    @Test fun `problem ids filter by group`() {
        val problems = listOf(
            SharedOutboxProblem.Failed(1, "g", SharedEntityType.EXPENSE, "e1", SharedOperation.SAVE, SharedError.NotFound, null),
            SharedOutboxProblem.Failed(2, "other", SharedEntityType.EXPENSE, "e2", SharedOperation.SAVE, SharedError.NotFound, null),
        )
        assertEquals(setOf("e1"), problemIds(problems, "g"))
        assertEquals(setOf("e1", "e2"), problemIds(problems))
    }

    // ── Debts ──

    @Test fun `debt lines name members and put mine first`() {
        val lines = debtLines(
            mapOf(
                "USD" to listOf(Debt(a, b, d("7.00"), "USD")),
                "INR" to listOf(Debt(a, b, d("100.00"), "INR"), Debt(me, a, d("50.00"), "INR")),
            ),
            me, names,
        )
        assertEquals(PersonRef.Me, lines.first().from)
        assertEquals(PersonRef.Named("Member A"), lines.first().to)
        assertEquals(listOf("INR-m-me-m-a", "INR-m-a-m-b", "USD-m-a-m-b"), lines.map { it.key })
    }

    // ── Members and invites ──

    @Test fun `seats list me first, removed last, with their state`() {
        val rows = seatRows(
            listOf(
                member(b, "Member B", removedAt = 3),
                member(a, "Member A", joined = false),
                member("m-c", "Member C"),
                member(me, "Me"),
            ),
            me,
        )
        assertEquals(listOf(me, a, "m-c", b), rows.map { it.id })
        assertEquals(listOf(SeatState.JOINED, SeatState.NOT_JOINED, SeatState.JOINED, SeatState.REMOVED), rows.map { it.state })
        assertTrue(rows.first().isMe)
    }

    @Test fun `invites soonest expiry first, seat invites named`() {
        val rows = inviteRows(
            listOf(
                SharedInviteInfo("i2", null, expiresAt = 200, uses = 3),
                SharedInviteInfo("i1", a, expiresAt = 100, uses = 0),
                SharedInviteInfo("i3", "m-unknown", expiresAt = 300, uses = 0),
            ),
            names,
        )
        assertEquals(
            listOf(
                InviteRow("i1", forSeat = true, seatName = "Member A", expiresAt = 100, uses = 0),
                InviteRow("i2", forSeat = false, seatName = null, expiresAt = 200, uses = 3),
                InviteRow("i3", forSeat = true, seatName = null, expiresAt = 300, uses = 0),
            ),
            rows,
        )
    }
}
