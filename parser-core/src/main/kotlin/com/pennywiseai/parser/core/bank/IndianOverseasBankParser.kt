package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal

/**
 * Parser for Indian Overseas Bank (IOB) SMS messages
 *
 * Common senders: VA-IOBCHN-S, XX-IOB-S, etc.
 *
 * SMS Format:
 * Your a/c no. XXXXX92 is credited by Rs.906.00 on 2025-08-28 17, from SIDDHANT SIN-7737219900@su(UPI Ref no 560699645381).Payer Remark - Paid via Supe -IOB
 *
 * A UPI payment arrives as two texts: one names the payee and the full 12-digit
 * reference ("debited for payee NAME. 250.00 on 2026-09-02, ref 612345678901"), the
 * other carries the balance and only the reference's first six digits
 * ("Rs.250.00 Debited to SB-xxx1234 ... [UPI/612345 ]"). Both are parsed; the app
 * books them once by matching the truncated reference against the full one.
 */
class IndianOverseasBankParser : BankParser() {

    /** "debited for payee JOHN DOE. 250.00 on 2026-09-02, ref 612345678901" */
    private val payeeDebit = Regex(
        """debited\s+for\s+payee\s+(.+?)\.\s+(?:Rs\.?\s*)?([0-9,]+(?:\.\d{1,2})?)\s+on\b""",
        RegexOption.IGNORE_CASE
    )

    /** "[UPI/612345 ]" in the balance text: the first digits of the UPI reference. */
    private val truncatedUpiReference = Regex("""\[UPI/(\d{4,12})\s*\]""", RegexOption.IGNORE_CASE)

    /** "Debited to SB-xxx1234" / "Credited to SB-xxx1234": the account, not a counterparty. */
    private val ownAccount = Regex("""(?:Debited|Credited)\s+to\s+SB-x*(\d{3,})""", RegexOption.IGNORE_CASE)

    override fun getBankName() = "Indian Overseas Bank"

    override fun canHandle(sender: String): Boolean {
        val normalizedSender = sender.uppercase()
        return normalizedSender.contains("IOB") ||
                normalizedSender.contains("IOBCHN")
    }

    override fun extractAmount(message: String): BigDecimal? {
        payeeDebit.find(message)?.let { match ->
            match.groupValues[2].replace(",", "").toBigDecimalOrNull()?.let { return it }
        }

        // List of amount patterns for IOB
        val amountPatterns = listOf(
            // "credited by Rs.906.00"
            Regex("""credited\s+by\s+Rs\.?\s*([0-9,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE),
            // "debited by Rs.906.00"
            Regex("""debited\s+by\s+Rs\.?\s*([0-9,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE),
            // "credited with Rs.906.00"
            Regex("""credited\s+with\s+Rs\.?\s*([0-9,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE),
            // "debited for Rs.906.00"
            Regex("""debited\s+for\s+Rs\.?\s*([0-9,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in amountPatterns) {
            pattern.find(message)?.let { match ->
                val amount = match.groupValues[1].replace(",", "")
                return try {
                    BigDecimal(amount)
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }

        return super.extractAmount(message)
    }

    override fun extractTransactionType(message: String): TransactionType? {
        val lowerMessage = message.lowercase()
        return when {
            lowerMessage.contains("credited by") -> TransactionType.INCOME
            lowerMessage.contains("credited with") -> TransactionType.INCOME
            lowerMessage.contains("is credited") -> TransactionType.INCOME

            lowerMessage.contains("debited by") -> TransactionType.EXPENSE
            lowerMessage.contains("debited for") -> TransactionType.EXPENSE
            lowerMessage.contains("is debited") -> TransactionType.EXPENSE

            else -> super.extractTransactionType(message)
        }
    }

    override fun extractMerchant(message: String, sender: String): String? {
        payeeDebit.find(message)?.let { match ->
            val payee = cleanMerchantName(match.groupValues[1].trim())
            if (isValidMerchantName(payee)) return payee
        }
        // The balance text names only this account ("Debited to SB-xxx1234").
        if (ownAccount.containsMatchIn(message)) return null

        // UPI transaction with payer details
        // Pattern: "from SIDDHANT SIN-7737219900@su(UPI Ref"
        val upiPayerPattern = Regex(
            """from\s+([^(]+?)(?:\(UPI|$)""",
            RegexOption.IGNORE_CASE
        )
        upiPayerPattern.find(message)?.let { match ->
            val payer = match.groupValues[1].trim()

            // Check if it contains UPI ID
            if (payer.contains("@")) {
                // Extract name and UPI ID
                val parts = payer.split("-")
                return if (parts.size >= 2) {
                    val name = cleanMerchantName(parts[0].trim())
                    val upiId = parts[1].trim()
                    "UPI - $name ($upiId)"
                } else {
                    "UPI - ${cleanMerchantName(payer)}"
                }
            } else {
                val cleanedPayer = cleanMerchantName(payer)
                if (isValidMerchantName(cleanedPayer)) {
                    return cleanedPayer
                }
            }
        }

        // Check for payer remark
        val remarkPattern = Regex(
            """Payer\s+Remark\s*-\s*([^-]+)""",
            RegexOption.IGNORE_CASE
        )
        remarkPattern.find(message)?.let { match ->
            val remark = cleanMerchantName(match.groupValues[1].trim())
            if (isValidMerchantName(remark) && !remark.equals("Paid via Supe", ignoreCase = true)) {
                return remark
            }
        }

        // Generic patterns for debit transactions
        if (message.contains("debited", ignoreCase = true)) {
            // Try to extract merchant from "to" or "for" patterns
            val toPattern = Regex(
                """(?:to|for)\s+([^,.-]+)""",
                RegexOption.IGNORE_CASE
            )
            toPattern.find(message)?.let { match ->
                val merchant = cleanMerchantName(match.groupValues[1].trim())
                if (isValidMerchantName(merchant)) {
                    return merchant
                }
            }
        }

        return super.extractMerchant(message, sender)
    }

    override fun extractAccountLast4(message: String): String? {
        ownAccount.find(message)?.let { return extractLast4Digits(it.groupValues[1]) }
        super.extractAccountLast4(message)?.let { return it }
        // Pattern: "Your a/c no. XXXXX92"
        val accountPattern = Regex(
            """a/c\s+no\.\s+([X\d]+)""",
            RegexOption.IGNORE_CASE
        )
        accountPattern.find(message)?.let { match ->
            return extractLast4Digits(match.groupValues[1])
        }

        return null
    }

    override fun extractReference(message: String): String? {
        // Payee text: ", ref 612345678901."
        Regex("""\bref\s+(\d{12})\b""", RegexOption.IGNORE_CASE).find(message)?.let {
            return it.groupValues[1]
        }
        truncatedUpiReference.find(message)?.let { return it.groupValues[1] }

        // Pattern: "(UPI Ref no 560699645381)"
        val upiRefPattern = Regex(
            """\(UPI\s+Ref\s+no\s+(\d+)\)""",
            RegexOption.IGNORE_CASE
        )
        upiRefPattern.find(message)?.let { match ->
            return match.groupValues[1]
        }

        // Alternative pattern without parentheses
        val altUpiRefPattern = Regex(
            """UPI\s+Ref\s+no\s+(\d+)""",
            RegexOption.IGNORE_CASE
        )
        altUpiRefPattern.find(message)?.let { match ->
            return match.groupValues[1]
        }

        return super.extractReference(message)
    }

    override fun isTransactionMessage(message: String): Boolean {
        val lowerMessage = message.lowercase()

        // Skip OTP and non-transaction messages
        if (lowerMessage.contains("otp") ||
            lowerMessage.contains("verification") ||
            lowerMessage.contains("request") ||
            lowerMessage.contains("failed")
        ) {
            return false
        }

        // Check for IOB specific transaction patterns
        if (lowerMessage.contains("is credited by") ||
            lowerMessage.contains("is debited by") ||
            lowerMessage.contains("credited with") ||
            lowerMessage.contains("debited for")
        ) {
            return true
        }

        return super.isTransactionMessage(message)
    }
}