package dev.rain.laundry

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.SavedDevice
import dev.rain.laundry.data.SessionVault
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the local encrypted vault; no request reaches a platform socket. */
@RunWith(AndroidJUnit4::class)
class SavedDevicesRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedHistoryIsPerOwnerAndStaleResponsesCannotWriteANewerAccount() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val preferences = context.getSharedPreferences("laundry_vault", 0)
        val previous = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        try {
            val vault = SessionVault(context)
            vault.login("saved-device-owner-a", "token-a")
            val ownerA = vault.owner()
            assertTrue(vault.saveSavedDevice(record(ownerA, "a", 10), ownerA, "token-a"))
            assertEquals(listOf("a"), vault.savedDevices().map { it.deviceId })

            // Logout hides the list but must not erase the owner's personal history.
            vault.logout()
            assertTrue(vault.savedDevices().isEmpty())

            vault.login("saved-device-owner-b", "token-b")
            assertTrue(vault.savedDevices().isEmpty())
            assertFalse(vault.saveSavedDevice(record(ownerA, "a", 20), ownerA, "token-a"))
            assertTrue(vault.savedDevices().isEmpty())

            vault.logout()
            vault.login("saved-device-owner-a", "token-a2")
            assertEquals(listOf("a"), vault.savedDevices().map { it.deviceId })
            assertTrue(vault.renameSavedDevice(vault.savedDevices().single(), "楼下"))
            assertEquals("楼下", vault.savedDevices().single().note)
            assertTrue(vault.deleteSavedDevice(vault.savedDevices().single()))
            assertTrue(vault.savedDevices().isEmpty())

            // A response started under the previous token cannot be saved into the new session.
            assertFalse(vault.saveSavedDevice(record(ownerA, "b", 30), ownerA, "token-a"))
            assertTrue(vault.savedDevices().isEmpty())
            assertTrue(vault.saveSavedDevice(record(ownerA, "b", 31), ownerA, "token-a2"))
            assertEquals(listOf("b"), vault.savedDevices().map { it.deviceId })
        } finally {
            preferences.edit().clear().apply {
                previous.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }

    private fun record(owner: String, id: String, time: Long) = SavedDevice(
        owner = owner,
        kind = "laundry",
        deviceId = id,
        codeKey = "IMEI",
        codeValue = "ABCD1234",
        name = "测试洗衣机",
        lastUsedAt = time
    )
}
