package com.pennywiseai.tracker.presentation.sharedexpenses

import com.pennywiseai.tracker.domain.loan.SplitRules
import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseDraft
import com.pennywiseai.tracker.domain.sharedexpenses.ExpenseTemplate
import com.pennywiseai.tracker.domain.sharedexpenses.PayerPart
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharePart
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMoney
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSplit
import com.pennywiseai.tracker.domain.sharedexpenses.SharedValidation
import com.pennywiseai.tracker.domain.sharedexpenses.SplitInput
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.domain.sharedexpenses.SplitResult
import java.math.BigDecimal
import java.time.LocalDate

// The expense form's pure parts: what it holds, how typed numbers are read, and
// the live preview (each member's Share, "left to assign", the first problem).
// No Android, no Compose. Every amount is in the form's one currency.

/** A member the forms offer. [removed] seats appear only when the row already names them. */
data class EditorMember(val id: String, val name: String, val isMe: Boolean, val removed: Boolean = false)

/**
 * Members a form offers: the group's active seats plus removed seats in [named]
 * (the row being edited names them; the server accepts those). Me first, then by name.
 */
fun editorMembers(members: List<SharedMember>, myMemberId: String?, named: Set<String> = emptySet()): List<EditorMember> =
    members.filter { it.removedAt == null || it.id in named }
        .map { EditorMember(it.id, it.displayName, it.id == myMemberId, it.removedAt != null) }
        .sortedWith(compareBy<EditorMember> { !it.isMe }.thenBy { it.removed }.thenBy { it.name.lowercase() }.thenBy { it.id })

/** Who paid: one member for the whole amount, or several with an amount each. */
enum class PaidBy { ONE, SEVERAL }

/** A form section a problem belongs to, so it shows next to that section. */
enum class FormField { DESCRIPTION, AMOUNT, CURRENCY, CATEGORY, NOTES, PAYERS, SHARES }

/** The section [this] error belongs to (from its server `field`). */
val SharedExpenseError.formField: FormField
    get() = when (field) {
        "description" -> FormField.DESCRIPTION
        "amount" -> FormField.AMOUNT
        "currency" -> FormField.CURRENCY
        "category" -> FormField.CATEGORY
        "notes" -> FormField.NOTES
        "payers" -> FormField.PAYERS
        else -> FormField.SHARES
    }

/**
 * The expense form as typed. Numbers stay the text the user typed (a comma or a
 * point as the decimal separator), so a field never reformats under the cursor.
 * Each split method keeps its own input, so switching methods loses nothing.
 */
data class ExpenseForm(
    val description: String = "",
    val amount: String = "",
    val currency: String = "INR",
    val date: LocalDate = LocalDate.of(2000, 1, 1),
    val category: String? = null,
    val notes: String = "",
    val paidBy: PaidBy = PaidBy.ONE,
    /** The one payer when [paidBy] is [PaidBy.ONE]. */
    val payerId: String? = null,
    /** What each member paid when [paidBy] is [PaidBy.SEVERAL]; blank means they did not pay. */
    val payerAmounts: Map<String, String> = emptyMap(),
    val method: SplitMethod = SplitMethod.EQUAL,
    /** EQUAL: the members taking part. */
    val equalIds: Set<String> = emptySet(),
    /** EXACT: an amount per member; blank or zero means no Share. */
    val exactAmounts: Map<String, String> = emptyMap(),
    /** PERCENT: a percent per member; blank or zero means no Share. */
    val percents: Map<String, String> = emptyMap(),
    /** SHARES: a whole-number count per member; zero means no Share. */
    val shareCounts: Map<String, Int> = emptyMap(),
    /** New expenses only: null for no repeat. Always set when editing a recurring expense. */
    val repeat: RecurringInterval? = null,
    /** Sections the user has changed; a problem shows inline only once its section is touched. */
    val touched: Set<FormField> = emptySet(),
)

/** What the form would save, worked out live. */
data class ExpensePreview(
    /** Each member's resulting Share, from [SharedSplit.split]; empty while the split can't be worked out. */
    val shares: Map<String, BigDecimal> = emptyMap(),
    /** Several payers: the amount minus what they paid. Null with one payer or no amount. */
    val payersLeft: BigDecimal? = null,
    /** EXACT: the amount minus the typed Shares (money). PERCENT: 100 minus the typed percents. Else null. */
    val sharesLeft: BigDecimal? = null,
    /** What would be saved; null while [error] is set. */
    val draft: ExpenseDraft? = null,
    /** The first problem, in the order the form reads; null when it can be saved. */
    val error: SharedExpenseError? = null,
)

private val HUNDRED = BigDecimal(100)

/** Up to 13 whole digits (amounts stay below 10^12 minor units) and the decimals allowed. */
private const val MAX_WHOLE_DIGITS = 13

/** Percents: what the server takes as a weight, up to 6 decimals. */
const val PERCENT_DECIMALS = 6

/** The most a SHARES count goes up to with the plus button. */
const val MAX_SHARE_COUNT = 999

/**
 * Whether [text] may stand in a number field while typing: digits with at most
 * one decimal separator (a point or a comma, whichever the keyboard gives) and
 * at most [decimals] digits after it. Blank is allowed.
 */
fun acceptNumberInput(text: String, decimals: Int): Boolean {
    if (text.isEmpty()) return true
    val separators = text.count { it == '.' || it == ',' }
    if (separators > 1 || (separators == 1 && decimals == 0)) return false
    if (text.any { !it.isDigit() && it != '.' && it != ',' }) return false
    val parts = text.split('.', ',')
    if (parts[0].length > MAX_WHOLE_DIGITS) return false
    return parts.size == 1 || parts[1].length <= decimals
}

/**
 * [text] as typed in a number field, read as a number: either separator, a
 * leading or trailing separator allowed ("5," is 5, ",5" is 0.5). Null for blank
 * or anything else.
 */
fun parseNumberInput(text: String): BigDecimal? = canonicalNumber(text)?.toBigDecimalOrNull()

/** [text] as the plain number the server takes ("33,5" → "33.5", "50." → "50"), or null. */
fun canonicalNumber(text: String): String? {
    var t = text.trim().replace(',', '.')
    if (t.isEmpty() || t == ".") return null
    if (!t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1) return null
    if (t.startsWith('.')) t = "0$t"
    if (t.endsWith('.')) t = t.dropLast(1)
    return t
}

/** How a stored amount reads in a field: no trailing zeros ("1240.00" → "1240", "12.50" → "12.5"). */
fun amountFieldText(amount: BigDecimal): String =
    if (amount.signum() == 0) "0" else amount.stripTrailingZeros().toPlainString()

/** Decimal places of [currency] (2 for INR, 0 for JPY). */
fun currencyDecimals(currency: String): Int = SplitRules.fractionDigits(currency)

/** A new expense: I pay, everyone active takes part equally (and with one share each for SHARES). */
fun newExpenseForm(group: SharedGroup, members: List<EditorMember>, today: LocalDate): ExpenseForm {
    val active = members.filter { !it.removed }.map { it.id }
    return ExpenseForm(
        currency = group.defaultCurrency,
        date = today,
        payerId = group.myMemberId?.takeIf { me -> members.any { it.id == me } } ?: active.firstOrNull(),
        equalIds = active.toSet(),
        shareCounts = active.associateWith { 1 },
    )
}

/** [form] filled from a bank transaction: its merchant, amount, currency, date and category. */
fun ExpenseForm.fromTransaction(
    merchant: String,
    amount: BigDecimal,
    currency: String,
    date: LocalDate,
    category: String?,
): ExpenseForm {
    val code = currency.uppercase()
    val validCurrency = SharedMoney.strictFractionDigits(code) != null
    return copy(
        description = merchant.trim(),
        amount = amountFieldText(amount.abs()),
        currency = if (validCurrency) code else this.currency,
        date = date,
        category = category?.trim()?.takeIf { it.isNotEmpty() } ?: this.category,
    )
}

/** The form for editing [expense]; the methods it doesn't use start from their defaults. */
fun expenseForm(expense: SharedExpense, members: List<EditorMember>): ExpenseForm =
    partsForm(
        description = expense.description, amount = expense.amount, currency = expense.currency, date = expense.date,
        category = expense.category, notes = expense.notes, method = expense.splitMethod,
        payers = expense.payers, shares = expense.shares, members = members, repeat = null,
    )

/** The form for editing a recurring expense's [template]; the date is the next due date. */
fun recurringForm(template: ExpenseTemplate, interval: RecurringInterval, nextDate: LocalDate, members: List<EditorMember>): ExpenseForm =
    partsForm(
        description = template.description, amount = template.amount, currency = template.currency, date = nextDate,
        category = template.category, notes = template.notes, method = template.splitMethod,
        payers = template.payers, shares = template.shares, members = members, repeat = interval,
    )

private fun partsForm(
    description: String,
    amount: BigDecimal,
    currency: String,
    date: LocalDate,
    category: String?,
    notes: String?,
    method: SplitMethod,
    payers: List<PayerPart>,
    shares: List<SharePart>,
    members: List<EditorMember>,
    repeat: RecurringInterval?,
): ExpenseForm {
    val active = members.filter { !it.removed }.map { it.id }
    val shareIds = shares.map { it.memberId }
    return ExpenseForm(
        description = description,
        amount = amountFieldText(amount),
        currency = currency,
        date = date,
        category = category,
        notes = notes.orEmpty(),
        paidBy = if (payers.size == 1) PaidBy.ONE else PaidBy.SEVERAL,
        payerId = payers.singleOrNull()?.memberId ?: payers.firstOrNull()?.memberId,
        payerAmounts = if (payers.size == 1) emptyMap() else payers.associate { it.memberId to amountFieldText(it.amount) },
        method = method,
        equalIds = if (method == SplitMethod.EQUAL) shareIds.toSet() else active.toSet(),
        exactAmounts = if (method == SplitMethod.EXACT) shares.associate { it.memberId to amountFieldText(it.amount) } else emptyMap(),
        percents = if (method == SplitMethod.PERCENT) shares.associate { it.memberId to it.weight.orEmpty() } else emptyMap(),
        shareCounts = if (method == SplitMethod.SHARES) {
            shares.associate { it.memberId to (SharedSplit.parseShareWeight(it.weight) ?: 1) }
        } else {
            active.associateWith { 1 }
        },
        repeat = repeat,
    )
}

/** The split input [form] holds for its method, limited to [offered] member IDs. */
fun splitInput(form: ExpenseForm, offered: Set<String>): SplitInput = when (form.method) {
    SplitMethod.EQUAL -> SplitInput.Equal(form.equalIds.intersect(offered))
    SplitMethod.EXACT -> SplitInput.Exact(
        form.exactAmounts.filterKeys { it in offered }.filterValues { it.isNotBlank() }
            .mapValues { (_, text) -> parseNumberInput(text) }
            // Zero means no Share; unreadable text stays in as zero, so it is refused rather than dropped.
            .filterValues { it == null || it.signum() != 0 }
            .mapValues { it.value ?: BigDecimal.ZERO },
    )
    SplitMethod.PERCENT -> SplitInput.Percent(
        form.percents.filterKeys { it in offered }.filterValues { it.isNotBlank() }
            .filterValues { parseNumberInput(it)?.signum() != 0 }
            .mapValues { (_, text) -> canonicalNumber(text) ?: text },
    )
    SplitMethod.SHARES -> SplitInput.Shares(form.shareCounts.filterKeys { it in offered }.filterValues { it > 0 })
}

/** The payers [form] holds, limited to [offered]; null when an amount is unreadable. */
private fun payerParts(form: ExpenseForm, amount: BigDecimal?, offered: Set<String>): List<PayerPart>? = when (form.paidBy) {
    PaidBy.ONE -> listOfNotNull(form.payerId?.takeIf { it in offered && amount != null }?.let { PayerPart(it, amount!!) })
    PaidBy.SEVERAL -> form.payerAmounts.filterKeys { it in offered }.filterValues { it.isNotBlank() }
        .map { (id, text) -> PayerPart(id, parseNumberInput(text) ?: return null) }
        .filter { it.amount.signum() != 0 }
        .sortedBy { it.memberId }
}

/**
 * The live preview of [form] among [members]: each member's Share computed with
 * [SharedSplit.split] (what is shown is what is saved), what is left to assign,
 * and the first problem, checked with [SharedValidation].
 */
fun previewExpense(form: ExpenseForm, members: List<EditorMember>): ExpensePreview {
    val offered = members.map { it.id }.toSet()
    val amount = parseNumberInput(form.amount)
    val digits = currencyDecimals(form.currency)

    val payersLeft = if (form.paidBy == PaidBy.SEVERAL && amount != null) {
        amount - form.payerAmounts.filterKeys { it in offered }.values.sumOf { parseNumberInput(it) ?: BigDecimal.ZERO }
    } else {
        null
    }
    val sharesLeft = when (form.method) {
        SplitMethod.EXACT -> amount?.let { a -> a - form.exactAmounts.filterKeys { it in offered }.values.sumOf { parseNumberInput(it) ?: BigDecimal.ZERO } }
        SplitMethod.PERCENT -> HUNDRED - form.percents.filterKeys { it in offered }.values.sumOf { parseNumberInput(it) ?: BigDecimal.ZERO }
        else -> null
    }

    val split = amount?.let { SharedSplit.split(it, form.currency, splitInput(form, offered)) }
    val shares = (split as? SplitResult.Success)?.shares.orEmpty()
    val payers = payerParts(form, amount, offered)

    val draft = ExpenseDraft(
        description = form.description,
        amount = amount ?: BigDecimal.ZERO,
        currency = form.currency,
        splitMethod = form.method,
        payers = payers.orEmpty(),
        shares = shares,
        category = form.category,
        notes = form.notes,
    )
    var error = SharedValidation.validateExpense(draft, offered)
    // An unreadable payer amount, or a split that can't be worked out, says why rather than "no payers" or "no shares".
    if (error != null && error.formField == FormField.PAYERS && payers == null) error = SharedExpenseError.PAYER_NOT_POSITIVE
    if (error != null && error.formField == FormField.SHARES && split is SplitResult.Failure) error = split.error
    return ExpensePreview(
        shares = shares.associate { it.memberId to it.amount },
        payersLeft = payersLeft?.let { padMoney(it, digits) },
        sharesLeft = sharesLeft?.let { if (form.method == SplitMethod.EXACT) padMoney(it, digits) else it.withoutTrailingZeros() },
        draft = if (error == null) draft else null,
        error = error,
    )
}

/** [value] with at least [digits] decimals, for showing money ("5" → "5.00"). */
private fun padMoney(value: BigDecimal, digits: Int): BigDecimal = if (value.scale() < digits) value.setScale(digits) else value

private fun BigDecimal.withoutTrailingZeros(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

/** [form] with [section] marked as changed. */
fun ExpenseForm.touch(section: FormField): ExpenseForm = if (section in touched) this else copy(touched = touched + section)

/** The problem to show inline at [section], if the user has touched it. */
fun ExpensePreview.errorAt(section: FormField, touched: Set<FormField>): SharedExpenseError? =
    error?.takeIf { it.formField == section && section in touched }
