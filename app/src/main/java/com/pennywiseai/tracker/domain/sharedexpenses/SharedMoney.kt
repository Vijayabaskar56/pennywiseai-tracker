package com.pennywiseai.tracker.domain.sharedexpenses

import com.pennywiseai.tracker.domain.loan.SplitRules
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Currency

/** Minor-unit helpers for Shared expenses. Each works in one currency only. */
internal object SharedMoney {
    private val ISO_CODE = Regex("^[A-Z]{3}$")

    /** Decimal places of [currency], or null when it isn't an uppercase ISO 4217 code. */
    fun strictFractionDigits(currency: String): Int? {
        if (!ISO_CODE.matches(currency)) return null
        if (runCatching { Currency.getInstance(currency) }.isFailure) return null
        return SplitRules.fractionDigits(currency)
    }

    /** True when [amount] has no more decimals than [digits] allows. */
    fun fitsMinorUnits(amount: BigDecimal, digits: Int): Boolean = amount.stripTrailingZeros().scale() <= digits

    /** [amount] in whole minor units; it must fit (see [fitsMinorUnits]). */
    fun toMinor(amount: BigDecimal, digits: Int): BigInteger = amount.movePointRight(digits).toBigIntegerExact()

    fun fromMinor(minor: BigInteger, digits: Int): BigDecimal = BigDecimal(minor, digits)

    /** [amount] printed with [currency]'s decimals ("620.00"); never rounds, only pads. */
    fun normalize(amount: BigDecimal, currency: String): BigDecimal {
        val digits = SplitRules.fractionDigits(currency)
        return if (amount.scale() < digits) amount.setScale(digits) else amount
    }
}
