package com.devanshu.devnex.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.devanshu.devnex.core.database.DevNexDatabase
import com.devanshu.devnex.data.remote.RemoteDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Flush the outbox as soon as there is a connection. */
    fun syncSoon() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("devnex-sync", ExistingWorkPolicy.KEEP, req)
    }

    /** Light periodic safety net (15 min is the platform minimum). */
    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("devnex-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/**
 * Sends pending low-conflict writes in order, in a batch. Each item is retried with
 * backoff; after 8 failed attempts it is dropped (the server already rejected it).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: DevNexDatabase,
    private val remote: RemoteDataSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (remote.currentUser() == null) return Result.success()
        var failed = false
        for (item in db.outbox().next()) {
            val ok = runCatching {
                when (item.kind) {
                    "message" -> { remote.sendMessage(item.refId, item.companyId, item.a, item.b); db.chat().markSent(item.refId) }
                    "checklist" -> remote.toggleChecklist(item.refId, item.a == "1")
                    "comment" -> remote.addComment(item.refId, item.companyId, item.a, item.b)
                    else -> Unit
                }
            }.isSuccess
            when {
                ok -> db.outbox().remove(item.seq)
                item.attempts >= 8 -> db.outbox().remove(item.seq)
                else -> { db.outbox().failed(item.seq); failed = true }
            }
        }
        return if (failed) Result.retry() else Result.success()
    }
}
