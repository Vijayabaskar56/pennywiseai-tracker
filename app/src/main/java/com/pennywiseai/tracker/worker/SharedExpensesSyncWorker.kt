package com.pennywiseai.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.data.sharedexpenses.SharedSyncManager
import com.pennywiseai.tracker.data.sharedexpenses.SharedSyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Pushes the Shared expenses outbox and pulls every group's changes (scheduled by SharedSyncScheduler). */
@HiltWorker
class SharedExpensesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: SharedSyncManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = manager.sync()
        return if (result.status == SharedSyncStatus.RETRY_LATER && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
