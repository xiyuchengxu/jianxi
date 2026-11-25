package dev.rain.laundry.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rain.laundry.core.WireProtocol
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Short-lived, network-constrained retries. WorkManager input stores only the owner hash, never the
 * owner, platform token, device token, server URL or 喵码. Once the server confirms the subscription,
 * monitoring continues server-side even if the app process exits.
 */
object CloudReminderWork {
    private const val SYNC_TAG = "cloud-reminder-sync"
    private const val UNBIND_TAG = "cloud-reminder-unbind"
    internal const val OWNER_HASH = "owner_hash"

    fun requestSync(context: Context, owner: String) {
        if (owner.isBlank()) return
        val hash = WireProtocol.sha256(owner)
        val request = OneTimeWorkRequestBuilder<CloudReminderSyncWorker>()
            .setInputData(Data.Builder().putString(OWNER_HASH, hash).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(SYNC_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$SYNC_TAG-$hash",
            ExistingWorkPolicy.KEEP, request)
    }

    fun requestUnbind(context: Context, owner: String) {
        if (owner.isBlank()) return
        val hash = WireProtocol.sha256(owner)
        val request = OneTimeWorkRequestBuilder<CloudUnbindWorker>()
            .setInputData(Data.Builder().putString(OWNER_HASH, hash).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNBIND_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$UNBIND_TAG-$hash",
            ExistingWorkPolicy.KEEP, request)
    }

    fun cancelUnbind(context: Context, owner: String) {
        if (owner.isBlank()) return
        WorkManager.getInstance(context).cancelUniqueWork("$UNBIND_TAG-${WireProtocol.sha256(owner)}")
    }
}

class CloudReminderSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val hash = inputData.getString(CloudReminderWork.OWNER_HASH)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return Result.success()
        val owner = SessionVault(applicationContext).owner()
        if (owner.isBlank() || WireProtocol.sha256(owner) != hash) return Result.success()
        return try {
            when (CloudReminderOperations(applicationContext).sync(owner)) {
                // A transient failure may outlive the first few attempts (offline device, server
                // restart). Keep the unique work alive with WorkManager's capped exponential
                // backoff so a still-running order can recover without requiring an app restart.
                CloudSyncOutcome.PENDING -> Result.retry()
                // Terminal rejections, an unsupported server and a tracked/waiting device report
                // need no immediate retry; the next save/start/monitor wake can re-evaluate.
                CloudSyncOutcome.AUTH_REQUIRED, CloudSyncOutcome.REJECTED,
                CloudSyncOutcome.UNSUPPORTED, CloudSyncOutcome.DEVICE_WAITING -> Result.success()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unexpected local failures are not the same as a durable transient server failure;
            // keep the old bounded retry so a corrupted vault cannot spin forever.
            if (runAttemptCount < 4) Result.retry() else Result.success()
        }
    }
}

class CloudUnbindWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val hash = inputData.getString(CloudReminderWork.OWNER_HASH)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return Result.success()
        return try {
            val outcome = CloudReminderOperations(applicationContext).retryPendingUnbindsHashed(hash)
            if (outcome.pending > 0 && runAttemptCount < 5) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
