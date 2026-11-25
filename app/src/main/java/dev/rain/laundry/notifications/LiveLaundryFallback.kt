package dev.rain.laundry.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.core.SessionExpired
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException

/**
 * Bounded fallback for when Android refuses a foreground-service start.
 *
 * This worker performs at most one read-only reconciliation and never enqueues a successor.
 * It does not post or repost a live notification; the existing completion/cloud monitor keeps
 * its own independent schedule.
 */
object LiveLaundryFallback {
    private const val TAG = "laundry-live-fallback"
    internal const val OWNER = "owner"
    internal const val ORDER_KEY = "order_key"

    fun enqueue(context: Context, owner: String, orderKey: String) {
        if (owner.isBlank() || orderKey.isBlank()) return
        val request = OneTimeWorkRequestBuilder<LiveLaundryFallbackWorker>()
            .setInputData(Data.Builder().putString(OWNER, owner).putString(ORDER_KEY, orderKey).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$TAG-${owner.hashCode()}-${orderKey.hashCode()}",
            ExistingWorkPolicy.KEEP, request)
    }
}

class LiveLaundryFallbackWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(LiveLaundryFallback.OWNER)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val key = inputData.getString(LiveLaundryFallback.ORDER_KEY)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val vault = SessionVault(applicationContext)
        if (vault.owner() != owner || vault.token().isBlank()) return Result.success()
        val current = vault.order() ?: return Result.success()
        if (current.owner != owner || !current.isLaundry || OrderHistory.key(current) != key)
            return Result.success()
        return try {
            // One attempt only. A failure is reported to the next foreground refresh; this worker
            // deliberately does not retry or chain another attempt.
            LaundryMonitorEngine.check(applicationContext, owner)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpired) {
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}
