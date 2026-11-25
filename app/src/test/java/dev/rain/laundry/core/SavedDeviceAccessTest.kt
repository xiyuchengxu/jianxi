package dev.rain.laundry.core

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavedDeviceAccessTest {
    @Test fun oldAuthenticationFailureCannotLogOutTheNewSession() = runTest {
        var current = true
        val stale = queryDeviceForCurrentSession({ current }) {
            current = false
            throw SessionExpired()
        }
        assertNull(stale)
        assertTrue(runCatching {
            queryDeviceForCurrentSession({ true }) { throw SessionExpired() }
        }.exceptionOrNull() is SessionExpired)
    }

    @Test fun staleNetworkFailureIsDiscardedButCurrentFailureIsReported() = runTest {
        var current = true
        assertNull(queryDeviceForCurrentSession({ current }) {
            current = false
            throw IOException("old connection")
        })
        assertTrue(runCatching {
            queryDeviceForCurrentSession({ true }) { throw IOException("current connection") }
        }.exceptionOrNull() is IOException)
    }

    @Test fun diskFailureLeavesTheFreshDeviceUsableWithoutRestoringAnOldSession() {
        assertEquals(SavedDeviceWrite.UNAVAILABLE,
            saveOptionalDeviceHistory({ true }) { throw IOException("disk full") })
        var current = true
        assertEquals(SavedDeviceWrite.STALE, saveOptionalDeviceHistory({ current }) {
            current = false
            throw IOException("old disk operation")
        })
        assertEquals(SavedDeviceWrite.STALE, saveOptionalDeviceHistory({ true }) { false })
        assertEquals(SavedDeviceWrite.SAVED, saveOptionalDeviceHistory({ true }) { true })
    }

    @Test fun cancellationAlwaysPropagates() = runTest {
        assertTrue(runCatching {
            queryDeviceForCurrentSession({ true }) { throw CancellationException() }
        }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching {
            saveOptionalDeviceHistory({ true }) { throw CancellationException() }
        }.exceptionOrNull() is CancellationException)
    }
}
