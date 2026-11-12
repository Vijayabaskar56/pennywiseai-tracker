package com.pennywiseai.tracker.data.backup

/**
 * The Shared expenses part of a backup, kept by that feature
 * (`data/sharedexpenses`), so the backup code does not reach into it.
 */
interface SharedExpensesBackupPort {
    /** This phone's section, or null when the feature was never used. */
    suspend fun export(): SharedExpensesBackup?

    /**
     * Called after the database restore committed. Restores [section] (null
     * for an older backup) and brings the budget in step with the restored
     * transactions. [replaceAll] is true after REPLACE_ALL, when this phone's
     * own transactions (and so its own links' targets) were replaced.
     */
    suspend fun restore(section: SharedExpensesBackup?, replaceAll: Boolean)

    companion object {
        val NONE = object : SharedExpensesBackupPort {
            override suspend fun export(): SharedExpensesBackup? = null
            override suspend fun restore(section: SharedExpensesBackup?, replaceAll: Boolean) = Unit
        }
    }
}
