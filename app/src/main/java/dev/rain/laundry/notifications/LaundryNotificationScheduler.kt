package dev.rain.laundry.notifications

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
import dev.rain.laundry.core.SessionExpired
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

data class LaundryMonitorCheck(val nextCheckAtMs: Long? = null, val retry: Boolean = false)

/** WorkManager observes network/Doze restrictions; scheduled times are never completion proof. */
object LaundryNotificationScheduler {
    private const val TAG = "laundry-completion-monitor"
    internal const val OWNER = "owner"
    private fun name(owner: String) = "$TAG-$owner"

    fun requestCheck(context: Context, owner: String, delayMs: Long = 0) {
        if (owner.isBlank()) return
        enqueue(context, owner, delayMs, ExistingWorkPolicy.REPLACE)
    }

    internal fun scheduleFollowing(context: Context, owner: String, delayMs: Long) {
        // A running worker appends its successor; replacing unique work here would cancel itself.
        enqueue(context, owner, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(context: Context, owner: String, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LaundryMonitorWorker>()
            .setInputData(Data.Builder().putString(OWNER, owner).build())
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(owner), policy, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
}

class LaundryMonitorWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(LaundryNotificationScheduler.OWNER)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val vault = SessionVault(applicationContext)
        if (vault.owner() != owner || vault.token().isBlank()) return Result.success()
        return try {
            val check = LaundryMonitorEngine.check(applicationContext, owner)
            if (vault.owner() != owner || vault.token().isBlank()) return Result.success()
            if (check.retry) Result.retry() else {
                check.nextCheckAtMs?.let { next ->
                    LaundryNotificationScheduler.scheduleFollowing(applicationContext, owner,
                        (next - System.currentTimeMillis()).coerceAtLeast(30_000))
                }
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            Result.failure()
        } catch (_: Exception) {
            // Unknown or offline status is retried, never converted to a finished washing cycle.
            Result.retry()
        }
    }
}
