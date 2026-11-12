package com.pennywiseai.tracker.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.pennywiseai.tracker.data.backup.SharedExpensesBackupPort
import com.pennywiseai.tracker.data.repository.TransactionHolds
import com.pennywiseai.tracker.data.sharedexpenses.BudgetLedger
import com.pennywiseai.tracker.data.sharedexpenses.RoomBudgetLedger
import com.pennywiseai.tracker.data.sharedexpenses.RoomSharedLinkStore
import com.pennywiseai.tracker.data.sharedexpenses.RoomSharedLocalStore
import com.pennywiseai.tracker.data.sharedexpenses.SharedApiClient
import com.pennywiseai.tracker.data.sharedexpenses.SharedBudgetLink
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesBackupAgent
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesDatabase
import com.pennywiseai.tracker.data.sharedexpenses.SharedIdentity
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkStore
import com.pennywiseai.tracker.data.sharedexpenses.SharedLocalStore
import com.pennywiseai.tracker.data.sharedexpenses.SharedRemote
import com.pennywiseai.tracker.data.sharedexpenses.SharedSession
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/** Shared expenses: its own Room database (shared_expenses.db), the server client and the session. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SharedExpensesModule {

    @Binds abstract fun bindLocalStore(store: RoomSharedLocalStore): SharedLocalStore

    @Binds abstract fun bindSession(identity: SharedIdentity): SharedSession

    @Binds abstract fun bindLinkStore(store: RoomSharedLinkStore): SharedLinkStore

    @Binds abstract fun bindLedger(ledger: RoomBudgetLedger): BudgetLedger

    /** TransactionRepository and LoanRepository ask it before writing a row a Shared group holds. */
    @Binds abstract fun bindTransactionHolds(link: SharedBudgetLink): TransactionHolds

    @Binds abstract fun bindBackupPort(agent: SharedExpensesBackupAgent): SharedExpensesBackupPort

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): SharedExpensesDatabase =
            Room.databaseBuilder(context, SharedExpensesDatabase::class.java, SharedExpensesDatabase.DATABASE_NAME)
                // System SQLite, as the main database uses.
                .setDriver(AndroidSQLiteDriver())
                .build()

        // OkHttp for platform TLS, as the AI relay client and webhooks use.
        @Provides
        @Singleton
        fun provideRemote(): SharedRemote = SharedApiClient(OkHttp.create())
    }
}
