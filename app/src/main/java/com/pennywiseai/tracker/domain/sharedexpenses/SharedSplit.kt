package com.pennywiseai.tracker.domain.sharedexpenses

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** What the expense form collected for one [SplitMethod]. */
sealed interface SplitInput {
    val method: SplitMethod

    /** The members taking part, each paying the same. */
    data class Equal(val memberIds: Set<String>) : SplitInput {
        override val method get() = SplitMethod.EQUAL
    }

    /** An amount per member, used as is. */
    data class Exact(val amounts: Map<String, BigDecimal>) : SplitInput {
        override val method get() = SplitMethod.EXACT
    }

    /** A percent per member as typed ("33.33"); together exactly 100. */
    data class Percent(val percents: Map<String, String>) : SplitInput {
        override val method get() = SplitMethod.PERCENT
    }

    /** A whole-number weight above zero per member. */
    data class Shares(val weights: Map<String, Int>) : SplitInput {
        override val method get() = SplitMethod.SHARES
    }
}

sealed interface SplitResult {
    /** The Shares, sorted by member ID, summing to the amount exactly. */
    data class Success(val shares: List<SharePart>) : SplitResult

    data class Failure(val error: SharedExpenseError) : SplitResult
}

/**
 * Turns split input into exact Share amounts. The saving phone does this once;
 * other phones show the stored amounts and never recompute them.
 *
 * EQUAL, PERCENT and SHARES give each member their exact portion rounded down
 * to the currency's minor unit, then hand the leftover minor units out one
 * each in ascending member-ID order. 1,000.00 INR between three members is
 * 333.34 for the lowest ID and 333.33 for the others.
 */
object SharedSplit {
    private val HUNDRED = BigDecimal(100)

    fun split(amount: BigDecimal, currency: String, input: SplitInput): SplitResult {
        val digits = SharedMoney.strictFractionDigits(currency)
            ?: return SplitResult.Failure(SharedExpenseError.INVALID_CURRENCY)
        SharedValidation.amountError(amount, digits)?.let { return SplitResult.Failure(it) }
        val total = SharedMoney.toMinor(amount, digits)

        return when (input) {
            is SplitInput.Equal -> {
                if (input.memberIds.isEmpty()) return SplitResult.Failure(SharedExpenseError.NO_SHARES)
                success(allocate(total, input.memberIds.associateWith { BigDecimal.ONE }), digits) { null }
            }
            is SplitInput.Exact -> exact(amount, digits, input.amounts)
            is SplitInput.Percent -> {
                if (input.percents.isEmpty()) return SplitResult.Failure(SharedExpenseError.NO_SHARES)
                val parsed = input.percents.mapValues { (_, typed) -> parsePercent(typed.trim()) }
                if (parsed.values.any { it == null }) return SplitResult.Failure(SharedExpenseError.PERCENT_INVALID)
                val percents = parsed.mapValues { it.value!! }
                if (percents.values.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(HUNDRED) != 0) {
                    return SplitResult.Failure(SharedExpenseError.PERCENT_NOT_100)
                }
                success(allocate(total, percents), digits) { input.percents.getValue(it).trim() }
            }
            is SplitInput.Shares -> {
                if (input.weights.isEmpty()) return SplitResult.Failure(SharedExpenseError.NO_SHARES)
                if (input.weights.values.any { it <= 0 }) return SplitResult.Failure(SharedExpenseError.SHARE_WEIGHT_INVALID)
                val weights = input.weights.mapValues { BigDecimal(it.value) }
                success(allocate(total, weights), digits) { input.weights.getValue(it).toString() }
            }
        }
    }

    // The weight format the server accepts: up to 12 digits, optionally up to 6 decimals.
    private val WEIGHT = Regex("^\\d{1,12}(\\.\\d{1,6})?$")
    private val WHOLE_WEIGHT = Regex("^\\d{1,9}$")

    /** A stored percent weight ("33.33") as a number above zero, or null. */
    internal fun parsePercent(weight: String?): BigDecimal? =
        weight?.takeIf { WEIGHT.matches(it) }?.toBigDecimal()?.takeIf { it.signum() > 0 }

    /** A stored share-count weight ("2") as a whole number above zero, or null. */
    internal fun parseShareWeight(weight: String?): Int? =
        weight?.takeIf { WHOLE_WEIGHT.matches(it) }?.toInt()?.takeIf { it > 0 }

    private fun exact(amount: BigDecimal, digits: Int, amounts: Map<String, BigDecimal>): SplitResult {
        val error = when {
            amounts.isEmpty() -> SharedExpenseError.NO_SHARES
            amounts.values.any { it.signum() <= 0 } -> SharedExpenseError.SHARE_NOT_POSITIVE
            amounts.values.any { !SharedMoney.fitsMinorUnits(it, digits) } -> SharedExpenseError.SHARE_TOO_MANY_DECIMALS
            amounts.values.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(amount) != 0 ->
                SharedExpenseError.SHARES_NOT_EQUAL_AMOUNT
            else -> null
        }
        if (error != null) return SplitResult.Failure(error)
        val shares = amounts.toSortedMap().map { (id, value) ->
            SharePart(id, if (value.scale() < digits) value.setScale(digits) else value, weight = null)
        }
        return SplitResult.Success(shares)
    }

    /**
     * [total] minor units split by [weights]: each member's exact portion
     * rounded down, then one leftover unit each in ascending member-ID order.
     */
    private fun allocate(total: BigInteger, weights: Map<String, BigDecimal>): Map<String, BigInteger> {
        val sum = weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val ids = weights.keys.sorted()
        val parts = ids.associateWithTo(LinkedHashMap()) { id ->
            BigDecimal(total).multiply(weights.getValue(id)).divide(sum, 0, RoundingMode.DOWN).toBigIntegerExact()
        }
        // Each portion loses less than one unit, so the leftover is below ids.size.
        var leftover = total - parts.values.fold(BigInteger.ZERO, BigInteger::add)
        var i = 0
        while (leftover.signum() > 0) {
            val id = ids[i % ids.size]
            parts[id] = parts.getValue(id) + BigInteger.ONE
            leftover -= BigInteger.ONE
            i++
        }
        return parts
    }

    // A member whose part rounds to zero (0.01 between three) gets no Share: the server refuses zero Shares.
    private fun success(parts: Map<String, BigInteger>, digits: Int, weight: (String) -> String?): SplitResult =
        SplitResult.Success(
            parts.filterValues { it.signum() > 0 }
                .map { (id, minor) -> SharePart(id, SharedMoney.fromMinor(minor, digits), weight(id)) },
        )
}
