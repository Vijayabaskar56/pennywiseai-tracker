package com.pennywiseai.tracker.data.sharedexpenses

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * The local copy of Shared groups, in its own file (`shared_expenses.db`) so the
 * main PennyWise schema's version numbers stay upstream's (docs/shared-expenses.md,
 * "App tables"). Not part of the PennyWise backup.
 */
@Database(
    entities = [
        SharedGroupEntity::class,
        SharedMemberEntity::class,
        SharedExpenseEntity::class,
        SharedExpensePayerEntity::class,
        SharedExpenseShareEntity::class,
        SharedSettlementEntity::class,
        SharedRecurringEntity::class,
        SharedActivityEntity::class,
        SharedOutboxEntity::class,
        SharedLinkEntity::class,
        SharedProjectionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SharedExpensesDatabase : RoomDatabase() {
    abstract fun groupDao(): SharedGroupDao
    abstract fun memberDao(): SharedMemberDao
    abstract fun expenseDao(): SharedExpenseDao
    abstract fun settlementDao(): SharedSettlementDao
    abstract fun recurringDao(): SharedRecurringDao
    abstract fun activityDao(): SharedActivityDao
    abstract fun outboxDao(): SharedOutboxDao
    abstract fun linkDao(): SharedLinkDao
    abstract fun projectionDao(): SharedProjectionDao

    companion object {
        const val DATABASE_NAME = "shared_expenses.db"
    }
}
