package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntity
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedError
import com.pennywiseai.tracker.data.sharedexpenses.SharedOperation
import com.pennywiseai.tracker.data.sharedexpenses.SharedOutboxProblem
import com.pennywiseai.tracker.domain.sharedexpenses.ActivityType
import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseTemplate
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.navigation.SharedExpenseDetail
import com.pennywiseai.tracker.navigation.SharedExpenseEditor
import com.pennywiseai.tracker.navigation.SharedRecurringList
import com.pennywiseai.tracker.navigation.SharedSettleUp
import com.pennywiseai.tracker.ui.UiText
import com.pennywiseai.tracker.utils.CurrencyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Activity lines, recurring rows, problem rows and the expense detail. Generic names only. */
class SharedActivityAndListsTest {

    private val me = "m-me"
    private val a = "m-a"
    private val b = "m-b"
    private val names = mapOf(me to "Me", a to "Member A", b to "Member B")

    private val you = UiText.Res(R.string.shared_person_you)
    private val youLower = UiText.Res(R.string.shared_person_you_lower)
    private val memberA = UiText.Plain("Member A")
    private val memberB = UiText.Plain("Member B")

    private fun d(v: String) = BigDecimal(v)
    private fun money(amount: String, currency: String = "INR") = CurrencyFormatter.formatCurrency(d(amount), currency)

    private fun activity(type: ActivityType, actor: String? = a, entity: String = "x", seq: Long = 1, createdAt: Long = 0, summary: Array<Pair<String, String>> = emptyArray()) =
        SharedActivity(seq = seq, groupId = "g", actorMemberId = actor, type = type, entityId = entity, summary = summary.toMap(), createdAt = createdAt)

    private fun text(a: SharedActivity) = activityText(a, me, names)

    private val groceries = arrayOf("description" to "Groceries", "amount" to "1240.00", "currency" to "INR")
    private val groceriesSubject = UiText.Res(R.string.shared_activity_subject_amount, listOf(UiText.Plain("Groceries"), money("1240.00")))

    // ── Activity lines, one per type ──

    @Test fun `group lines`() {
        assertEquals(UiText.Res(R.string.shared_activity_group_created, listOf(memberA, "Flat")), text(activity(ActivityType.GROUP_CREATED, summary = arrayOf("name" to "Flat"))))
        assertEquals(UiText.Res(R.string.shared_activity_group_deleted, listOf(you, "Flat")), text(activity(ActivityType.GROUP_DELETED, actor = me, summary = arrayOf("name" to "Flat"))))
        assertEquals(
            UiText.Res(R.string.shared_activity_group_renamed, listOf(memberA, "Flat", "Home")),
            text(activity(ActivityType.GROUP_RENAMED, summary = arrayOf("oldName" to "Flat", "name" to "Home"))),
        )
        assertEquals(UiText.Res(R.string.shared_activity_group_renamed_to, listOf(memberA, "Home")), text(activity(ActivityType.GROUP_RENAMED, summary = arrayOf("name" to "Home"))))
    }

    @Test fun `group settings lines`() {
        assertEquals(
            UiText.Res(R.string.shared_activity_settings_currency, listOf(memberA, "USD")),
            text(activity(ActivityType.GROUP_SETTINGS_CHANGED, summary = arrayOf("defaultCurrency" to "USD"))),
        )
        assertEquals(UiText.Res(R.string.shared_activity_settings_simplify_on, listOf(memberA)), text(activity(ActivityType.GROUP_SETTINGS_CHANGED, summary = arrayOf("simplifyDebts" to "true"))))
        assertEquals(UiText.Res(R.string.shared_activity_settings_simplify_off, listOf(memberA)), text(activity(ActivityType.GROUP_SETTINGS_CHANGED, summary = arrayOf("simplifyDebts" to "false"))))
        assertEquals(
            UiText.Res(R.string.shared_activity_settings_changed, listOf(memberA)),
            text(activity(ActivityType.GROUP_SETTINGS_CHANGED, summary = arrayOf("simplifyDebts" to "false", "defaultCurrency" to "USD"))),
        )
    }

    @Test fun `member lines`() {
        assertEquals(
            UiText.Res(R.string.shared_activity_member_added, listOf(you, UiText.Plain("Member B"))),
            text(activity(ActivityType.MEMBER_ADDED, actor = me, entity = b, summary = arrayOf("memberName" to "Member B"))),
        )
        assertEquals(
            UiText.Res(R.string.shared_activity_member_joined, listOf(memberB)),
            text(activity(ActivityType.MEMBER_JOINED, actor = b, entity = b, summary = arrayOf("memberName" to "Member B"))),
        )
        // A joiner this phone doesn't know yet: the recorded name.
        assertEquals(
            UiText.Res(R.string.shared_activity_member_joined, listOf(UiText.Plain("Newcomer"))),
            text(activity(ActivityType.MEMBER_JOINED, actor = "m-new", entity = "m-new", summary = arrayOf("memberName" to "Newcomer"))),
        )
        assertEquals(UiText.Res(R.string.shared_activity_member_left, listOf(memberA)), text(activity(ActivityType.MEMBER_REMOVED, actor = a, entity = a, summary = arrayOf("memberName" to "Member A"))))
        assertEquals(
            UiText.Res(R.string.shared_activity_member_removed, listOf(memberA, youLower)),
            text(activity(ActivityType.MEMBER_REMOVED, actor = a, entity = me, summary = arrayOf("memberName" to "Me"))),
        )
        assertEquals(
            UiText.Res(R.string.shared_activity_member_renamed, listOf(memberA, "Old", "Member B")),
            text(activity(ActivityType.MEMBER_RENAMED, entity = b, summary = arrayOf("oldName" to "Old", "memberName" to "Member B"))),
        )
        assertEquals(
            UiText.Res(R.string.shared_activity_member_renamed_to, listOf(memberA, "Member B")),
            text(activity(ActivityType.MEMBER_RENAMED, entity = b, summary = arrayOf("memberName" to "Member B"))),
        )
    }

    @Test fun `expense lines carry the description and the amount in its currency`() {
        mapOf(
            ActivityType.EXPENSE_ADDED to R.string.shared_activity_expense_added,
            ActivityType.EXPENSE_EDITED to R.string.shared_activity_expense_edited,
            ActivityType.EXPENSE_DELETED to R.string.shared_activity_expense_deleted,
            ActivityType.EXPENSE_RESTORED to R.string.shared_activity_expense_restored,
        ).forEach { (type, res) ->
            assertEquals(UiText.Res(res, listOf(memberA, groceriesSubject)), text(activity(type, summary = groceries)))
        }
        // Missing amount: the description alone.
        assertEquals(
            UiText.Res(R.string.shared_activity_expense_added, listOf(you, UiText.Plain("Groceries"))),
            text(activity(ActivityType.EXPENSE_ADDED, actor = me, summary = arrayOf("description" to "Groceries"))),
        )
    }

    @Test fun `a recurring expense coming due reads as Recurring`() {
        assertEquals(
            UiText.Res(R.string.shared_activity_expense_added, listOf(UiText.Res(R.string.shared_actor_recurring), groceriesSubject)),
            text(activity(ActivityType.EXPENSE_ADDED, actor = null, summary = groceries)),
        )
        assertEquals(UiText.Res(R.string.shared_person_unknown), actorText("m-stranger", me, names))
    }

    @Test fun `settlement lines name who paid whom`() {
        val summary = arrayOf("fromMemberId" to me, "toMemberId" to a, "amount" to "500.00", "currency" to "USD")
        mapOf(
            ActivityType.SETTLEMENT_ADDED to R.string.shared_activity_settlement_added,
            ActivityType.SETTLEMENT_EDITED to R.string.shared_activity_settlement_edited,
            ActivityType.SETTLEMENT_DELETED to R.string.shared_activity_settlement_deleted,
        ).forEach { (type, res) ->
            assertEquals(UiText.Res(res, listOf(you, youLower, memberA, money("500.00", "USD"))), text(activity(type, actor = me, summary = summary)))
        }
    }

    @Test fun `recurring lines carry the interval`() {
        val summary = arrayOf("description" to "Rent", "amount" to "300.00", "currency" to "INR", "interval" to "MONTHLY")
        val subject = UiText.Res(R.string.shared_activity_subject_amount, listOf(UiText.Plain("Rent"), money("300.00")))
        val monthly = UiText.Res(R.string.shared_activity_interval_monthly)
        assertEquals(UiText.Res(R.string.shared_activity_recurring_added, listOf(memberA, subject, monthly)), text(activity(ActivityType.RECURRING_ADDED, summary = summary)))
        assertEquals(UiText.Res(R.string.shared_activity_recurring_edited, listOf(memberA, subject, monthly)), text(activity(ActivityType.RECURRING_EDITED, summary = summary)))
        assertEquals(UiText.Res(R.string.shared_activity_recurring_stopped, listOf(memberA, subject, monthly)), text(activity(ActivityType.RECURRING_STOPPED, summary = summary)))
    }

    @Test fun `every activity type has a line of its own`() {
        val ids = ActivityType.entries.map { (text(activity(it, summary = groceries)) as UiText.Res).id }
        assertEquals(ActivityType.entries.size, ids.toSet().size)
    }

    // ── Activity rows ──

    @Test fun `rows are newest first, link expenses on this phone, and offer restore once`() {
        val info = ActivityGroupInfo("Flat", me, names, deletedExpenses = setOf("e1"), liveExpenses = setOf("e2"))
        val rows = activityRows(
            listOf(
                activity(ActivityType.EXPENSE_DELETED, entity = "e1", seq = 1, createdAt = 10, summary = groceries),
                activity(ActivityType.EXPENSE_DELETED, entity = "e1", seq = 3, createdAt = 30, summary = groceries),
                activity(ActivityType.EXPENSE_ADDED, entity = "e2", seq = 2, createdAt = 20, summary = groceries),
                activity(ActivityType.EXPENSE_ADDED, entity = "e-gone", seq = 4, createdAt = 5, summary = groceries),
                activity(ActivityType.MEMBER_ADDED, entity = b, seq = 5, createdAt = 1, summary = arrayOf("memberName" to "Member B")),
            ),
            mapOf("g" to info),
            showGroup = true,
        )
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), rows.map { it.activity.seq })
        assertEquals(listOf(true, false, false, false, false), rows.map { it.canRestore })
        assertEquals(listOf("e1", "e2", "e1", null, null), rows.map { it.expenseId })
        assertEquals("Flat", rows.first().groupName)
        assertNull(activityRows(rows.map { it.activity }, mapOf("g" to info), showGroup = false).first().groupName)
    }

    // ── Recurring rows ──

    private fun recurring(id: String, description: String, next: LocalDate, pending: Boolean = false, deletedAt: Long? = null) = SharedRecurring(
        id = id, groupId = "g",
        template = ExpenseTemplate(description, d("300.00"), "INR", splitMethod = SplitMethod.EQUAL, payers = listOf(PayerPart(me, d("300.00"))), shares = listOf(SharePart(me, d("300.00")))),
        interval = RecurringInterval.MONTHLY, anchorDay = 1, nextDate = next, createdBy = me, updatedBy = me,
        createdAt = 0, updatedAt = 0, deletedAt = deletedAt, version = 1, rev = 1, pending = pending,
    )

    @Test fun `recurring rows are next due first and leave out stopped ones`() {
        val rows = recurringRows(
            listOf(
                recurring("r1", "Rent", LocalDate.of(2026, 12, 1)),
                recurring("r2", "Internet", LocalDate.of(2026, 11, 20), pending = true),
                recurring("r3", "Gym", LocalDate.of(2026, 11, 15), deletedAt = 5),
            ),
            problems = setOf("r1"),
        )
        assertEquals(listOf("r2", "r1"), rows.map { it.id })
        assertEquals(listOf(SyncMark.PENDING, SyncMark.PROBLEM), rows.map { it.mark })
    }

    @Test fun `a recurring row reads interval and next date`() {
        val row = recurringRows(listOf(recurring("r1", "Rent", LocalDate.of(2026, 12, 1)))).single()
        assertEquals(
            UiText.Res(R.string.shared_recurring_row, listOf(UiText.Res(R.string.shared_repeat_monthly), formDate(LocalDate.of(2026, 12, 1)))),
            recurringSubtitle(row),
        )
        assertEquals(R.string.shared_repeat_weekly, intervalRes(RecurringInterval.WEEKLY))
        assertEquals(R.string.shared_repeat_yearly, intervalRes(RecurringInterval.YEARLY))
    }

    // ── Problems ──

    private fun expense(id: String, amount: String, updatedBy: String = me, deletedAt: Long? = null) = SharedExpense(
        id = id, groupId = "g", description = "Groceries", amount = d(amount), currency = "INR", date = LocalDate.of(2026, 11, 10),
        splitMethod = SplitMethod.EQUAL, payers = listOf(PayerPart(me, d(amount))), shares = listOf(SharePart(me, d(amount))),
        createdBy = me, updatedBy = updatedBy, createdAt = 0, updatedAt = 0, deletedAt = deletedAt, version = 2, rev = 1,
    )

    @Test fun `a conflict compares my version with theirs`() {
        val problem = SharedOutboxProblem.Conflict(
            7, "g", SharedEntityType.EXPENSE, "e1", SharedOperation.SAVE,
            mine = SharedEntity.Expense(expense("e1", "100")), theirs = SharedEntity.Expense(expense("e1", "120", updatedBy = a)),
        )
        val row = problemRow(problem, me, names, "Flat")
        val kind = row.kind as ProblemKind.Conflict
        assertEquals(0, d("100").compareTo(kind.mine!!.amount))
        assertEquals(0, d("120").compareTo(kind.theirs.amount))
        assertEquals(PersonRef.Named("Member A"), kind.theirs.changedBy)
        assertEquals(PersonRef.Me, kind.mine!!.changedBy)
        assertTrue(row.mineLive)
        assertEquals(SharedExpenseDetail("g", "e1"), row.openKey())
    }

    @Test fun `a refused change keeps its reason, and open goes to the right place`() {
        val settlement = SharedSettlement(
            id = "s1", groupId = "g", fromMemberId = me, toMemberId = a, amount = d("50"), currency = "INR", date = LocalDate.of(2026, 11, 1),
            createdBy = me, updatedBy = me, createdAt = 0, updatedAt = 0, version = 1, rev = 1,
        )
        val failed = SharedOutboxProblem.Failed(3, "g", SharedEntityType.SETTLEMENT, "s1", SharedOperation.SAVE, SharedError.NotFound, SharedEntity.Settlement(settlement))
        val row = problemRow(failed, me, names, null)
        assertEquals(ProblemKind.Failed(SharedError.NotFound), row.kind)
        assertEquals(PersonRef.Me, row.facts!!.from)
        assertEquals(SharedSettleUp("g", settlementId = "s1"), row.openKey())

        val gone = problemRow(failed.copy(mine = null), me, names, null)
        assertNull(gone.openKey())

        val recurringRow = problemRow(
            SharedOutboxProblem.Failed(4, "g", SharedEntityType.RECURRING, "r1", SharedOperation.SAVE, SharedError.NotFound, SharedEntity.Recurring(recurring("r1", "Rent", LocalDate.of(2026, 12, 1)))),
            me, names, null,
        )
        assertEquals(SharedExpenseEditor("g", recurringId = "r1"), recurringRow.openKey())
        assertEquals(SharedRecurringList("g"), recurringRow.copy(mineLive = false).openKey())
    }

    // ── Expense detail ──

    @Test fun `detail lists me first and keeps typed weights only for percent and shares`() {
        val e = expense("e1", "1000").copy(
            splitMethod = SplitMethod.PERCENT,
            payers = listOf(PayerPart(b, d("1000"))),
            shares = listOf(SharePart(b, d("500"), "50"), SharePart(me, d("500"), "50")),
            updatedBy = a, updatedAt = 10, pending = true,
        )
        val detail = expenseDetail(e, me, names)
        assertEquals(listOf(PersonRef.Me, PersonRef.Named("Member B")), detail.shares.map { it.who })
        assertEquals(listOf("50", "50"), detail.shares.map { it.weight })
        assertEquals(PersonRef.Named("Member A"), detail.updatedBy)
        assertTrue(detail.edited)
        assertTrue(detail.pending)
        assertFalse(detail.deleted)

        val exact = expenseDetail(e.copy(splitMethod = SplitMethod.EXACT, shares = listOf(SharePart(me, d("1000"), "1"))), me, names)
        assertNull(exact.shares.single().weight)
    }
}
