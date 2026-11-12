package com.pennywiseai.tracker.data.sharedexpenses

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pennywiseai.tracker.worker.SharedExpensesSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules [SharedExpensesSyncWorker]: every hour while the feature is in use, and once after each local change. */
@Singleton
class SharedSyncScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val work by lazy { WorkManager.getInstance(context) }
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Starts the periodic sync; call only once a server address and a registration exist. Repeated calls keep
     * the schedule's timing. Hourly, because this is also how the phone learns of other members' changes.
     */
    fun ensurePeriodic() {
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SharedExpensesSyncWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }

    /** Stops all scheduled syncs (the registration was forgotten). */
    fun cancel() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(ONE_SHOT)
    }

    /**
     * A sync as soon as there is a network. Replaces one that is waiting or
     * running, so a change made during a sync is always sent; cutting a send
     * short is safe because every send can be repeated.
     */
    fun syncSoon() {
        work.enqueueUniqueWork(
            ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SharedExpensesSyncWorker>().setConstraints(constraints).build(),
        )
    }

    companion object {
        const val PERIODIC = "pennywise_shared_expenses_periodic_sync"
        const val ONE_SHOT = "pennywise_shared_expenses_sync_now"
    }
}
