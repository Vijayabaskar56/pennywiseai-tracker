package com.pennywiseai.tracker.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.pennywiseai.tracker.data.sharedexpenses.RoomSharedLocalStore
import com.pennywiseai.tracker.data.sharedexpenses.SharedApiClient
import com.pennywiseai.tracker.data.sharedexpenses.SharedExpensesDatabase
import com.pennywiseai.tracker.data.sharedexpenses.SharedIdentity
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
