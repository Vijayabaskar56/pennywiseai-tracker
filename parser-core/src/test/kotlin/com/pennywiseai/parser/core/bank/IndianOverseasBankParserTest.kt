package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.test.ExpectedTransaction
import com.pennywiseai.parser.core.test.ParserTestCase
import com.pennywiseai.parser.core.test.ParserTestUtils
import com.pennywiseai.parser.core.test.SimpleTestCase
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class IndianOverseasBankParserTest {

    @TestFactory
    fun `indian overseas bank parser handles common cases`(): List<DynamicTest> {
        val parser = IndianOverseasBankParser()

        val cases = listOf(
            ParserTestCase(
                name = "UPI payment to a payee (EXPENSE)",
                message = "Your a/c XXXXX12 debited for payee JOHN DOE. 250.00 on 2026-09-02, ref 612345678901.If not you, report to your bank immediately-IOB.",
                sender = "VM-IOBCHN",
                expected = ExpectedTransaction(
                    amount = BigDecimal("250.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "JOHN DOE",
                    reference = "612345678901"
                )
            ),
            // IOB sends a second, balance-only text for the same UPI payment. Its
            // "[UPI/612345 ]" is the first six digits of the payee text's reference,
            // which the app uses to book the payment once.
            ParserTestCase(
                name = "Balance text for the same UPI debit carries the truncated reference (EXPENSE)",
                message = "Rs.250.00 Debited to SB-xxx1234 AcBal:1750.25 CLRBal: 1750.25 [UPI/612345 ] SAMPLE BRANCH on 02-09-2026 10:59:20.IOB.",
                sender = "AD-IOBCHN",
                expected = ExpectedTransaction(
                    amount = BigDecimal("250.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    reference = "612345",
                    accountLast4 = "1234",
                    balance = BigDecimal("1750.25"),
                    expectNullMerchant = true
                )
            ),
            ParserTestCase(
                name = "Balance text for a UPI credit carries the truncated reference (INCOME)",
                message = "Rs.1200.00 Credited to SB-xxx1234 AcBal:2950.25 CLRBal: 2950.25 [UPI/698765 ] SAMPLE BRANCH on 03-09-2026 18:04:11.IOB.",
                sender = "VK-IOBCHN",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1200.00"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    reference = "698765",
                    accountLast4 = "1234",
                    balance = BigDecimal("2950.25"),
                    expectNullMerchant = true
                )
            ),
            ParserTestCase(
                name = "UPI credit with payer (INCOME)",
                message = "Your a/c no. XXXXX12 is credited by Rs.906.00 on 2025-08-28 17, from JANE ROE-9876543210@ybl(UPI Ref no 560612345678).Payer Remark - UPI -IOB",
                sender = "VA-IOBCHN-S",
                expected = ExpectedTransaction(
                    amount = BigDecimal("906.00"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    reference = "560612345678"
                )
            ),
            ParserTestCase(
                name = "NEFT credit balance text has no UPI reference (INCOME)",
                message = "Rs.4750.00 Credited to SB-xxx1234 AcBal:9950.25 CLRBal: 9950.25 [NEFT-CITI- ] SAMPLE BRANCH on 20-09-2026 11:30:02.IOB.",
                sender = "VK-IOBCHN",
                expected = ExpectedTransaction(
                    amount = BigDecimal("4750.00"),
                    currency = "INR",
                    type = TransactionType.INCOME
                )
            ),
            ParserTestCase(
                name = "OTP is not a transaction",
                message = "123456 is the OTP for your IOB transaction of Rs.500.00. Do not share it with anyone.",
                sender = "VM-IOBCHN",
                shouldParse = false
            )
        )

        val handleCases = listOf(
            "VM-IOBCHN" to true,
            "JD-IOBCHN-S" to true,
            "VA-IOBCHN-S" to true
        )

        return ParserTestUtils.runTestSuite(parser, cases, handleCases, "Indian Overseas Bank Parser")
    }

    @TestFactory
    fun `factory resolves indian overseas bank`(): List<DynamicTest> {
        val cases = listOf(
            SimpleTestCase(
                bankName = "Indian Overseas Bank",
                sender = "AX-IOBCHN",
                currency = "INR",
                message = "Your a/c XXXXX12 debited for payee JOHN DOE. 250.00 on 2026-09-02, ref 612345678901.If not you, report to your bank immediately-IOB.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("250.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE
                ),
                shouldHandle = true
            )
        )
        return ParserTestUtils.runFactoryTestSuite(cases, "IOB factory")
    }
}
