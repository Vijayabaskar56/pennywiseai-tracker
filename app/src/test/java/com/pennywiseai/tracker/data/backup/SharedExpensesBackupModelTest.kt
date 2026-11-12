package com.pennywiseai.tracker.data.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Shared expenses section of a backup (docs/backup-format.md). Synthetic values only. */
class SharedExpensesBackupModelTest {

    @Test
    fun oldBackupMissingSharedExpenses_decodesToNull() {
        val oldShape = """{"_format":"PennyWise Backup v1.3","database":{},"preferences":{}}"""
        val restored = backupJson.decodeFromString<PennyWiseBackup>(oldShape)
        assertNull(restored.sharedExpenses)
    }

    @Test
    fun sharedExpensesSectionMissingFields_takesDefaults() {
        val partial = """{"_format":"PennyWise Backup v1.3","shared_expenses":{"links":[{"entity_type":"EXPENSE"}]}}"""
        val section = backupJson.decodeFromString<PennyWiseBackup>(partial).sharedExpenses!!
        assertNull(section.identity)
        assertEquals("", section.links.single().transactionHash)
        assertNull(section.links.single().originalState)
        assertEquals(emptyList<SharedDismissedEntryBackup>(), section.dismissedEntries)
    }

    @Test
    fun sharedExpensesSection_roundTrips() {
        val backup = PennyWiseBackup(
            sharedExpenses = SharedExpensesBackup(
                identity = SharedIdentityBackup("https://shared.example.com", "user-1", "secret-1", "Member A"),
                links = listOf(
                    SharedLinkBackup("g1", "EXPENSE", "e1", "sms-hash-1", null, 5L),
                    SharedLinkBackup("g1", "SETTLEMENT", "s1", "sms-hash-2", "excluded_from_analytics=0", 6L),
                ),
                dismissedEntries = listOf(SharedDismissedEntryBackup("g1", "e2")),
            ),
        )
        val restored = backupJson.decodeFromString<PennyWiseBackup>(backupJson.encodeToString(backup))
        assertEquals(backup.sharedExpenses, restored.sharedExpenses)
    }
}
