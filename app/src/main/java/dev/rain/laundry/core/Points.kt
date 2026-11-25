package dev.rain.laundry.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PointsTask(
    val id: String,
    val name: String,
    val reward: String? = null,
    val completed: Int? = null,
    val limit: Int? = null,
    val done: Boolean = false,
    val description: String = ""
)

data class PointsSnapshot(val balance: String, val signedInToday: Boolean, val tasks: List<PointsTask>)

data class PointsUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val running: Boolean = false,
    val balance: String? = null,
    val signedInToday: Boolean? = null,
    val tasks: List<PointsTask> = emptyList(),
    val progress: String = "",
    val error: String? = null,
    val logs: List<String> = emptyList()
) {
    fun withSnapshot(value: PointsSnapshot) = copy(loaded = true, balance = value.balance,
        signedInToday = value.signedInToday, tasks = value.tasks)
}

interface PointsGateway {
    suspend fun balance(): String
    suspend fun tasks(): List<PointsTask>
    suspend fun signedInToday(): Boolean
    suspend fun signInActivity(): String
    suspend fun signIn(activityId: String)
}

class PointsDataInvalid : Exception()
class PointsResultUnknown : Exception()

/** Only daily sign-in is automated. Task completion requires its actual platform workflow. */
class PointsWorkflow(private val gateway: PointsGateway, private val current: () -> Boolean = { true }) {
    private suspend fun <T> checked(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("Points session changed")
        val value = action()
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("Points session changed")
        return value
    }

    suspend fun refresh(): PointsSnapshot {
        val balance = checked { gateway.balance() }
        val signed = checked { gateway.signedInToday() }
        val tasks = checked { gateway.tasks() }
        return PointsSnapshot(balance, signed, tasks)
    }

    /** A failed mutation is reconciled once by a read query, never by replaying the mutation. */
    suspend fun autoSignIn(): Boolean {
        if (checked { gateway.signedInToday() }) return false
        val activity = checked { gateway.signInActivity() }
        try {
            checked { gateway.signIn(activity) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expired: SessionExpired) {
            throw expired
        } catch (_: Exception) {
            // This includes an already-signed response caused by another client signing in.
            // A successful current-status query is the only way to reconcile it.
            if (!confirmedAfterMutation()) throw PointsResultUnknown()
            return true
        }
        if (!confirmedAfterMutation()) throw PointsResultUnknown()
        return true
    }

    private suspend fun confirmedAfterMutation(): Boolean = try {
        checked { gateway.signedInToday() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expired: SessionExpired) {
        throw expired
    } catch (_: Exception) {
        false
    }
}
