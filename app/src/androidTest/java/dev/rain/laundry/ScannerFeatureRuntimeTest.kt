package dev.rain.laundry

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.ScanOptions
import dev.rain.laundry.scanner.ScannerFeature
import dev.rain.laundry.scanner.ScreenCodeCaptureActivity
import dev.rain.laundry.ui.ThemePreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local preference and camera-entry checks only; no account, device or network requests. */
@RunWith(AndroidJUnit4::class)
class ScannerFeatureRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun scannerDefaultAndPersistenceAreIndependentOfServiceSwitches() = withPreferences {
        val prefs = context.getSharedPreferences(ScannerFeature.PREFERENCES, 0)
        prefs.edit().remove(ScannerFeature.KEY_ENABLED).commit()
        assertTrue(ScannerFeature.isEnabled(context))
        val preferences = ThemePreferences(context)
        preferences.update(preferences.settings.copy(scannerEnabled = false,
            laundryEnabled = true, waterEnabled = false))
        assertFalse(ScannerFeature.isEnabled(context))
        val reloaded = ThemePreferences(context).settings
        assertFalse(reloaded.scannerEnabled)
        assertTrue(reloaded.laundryEnabled)
        assertFalse(reloaded.waterEnabled)
        preferences.update(preferences.settings.copy(scannerEnabled = true))
        assertTrue(ScannerFeature.isEnabled(context))
        assertFalse(ThemePreferences(context).settings.waterEnabled)
    }

    @Test fun disabledScannerActivityClosesBeforeCameraResume() = withPreferences {
        context.getSharedPreferences(ScannerFeature.PREFERENCES, 0).edit()
            .putBoolean(ScannerFeature.KEY_ENABLED, false).commit()
        val intent = ScanOptions().setCaptureActivity(ScreenCodeCaptureActivity::class.java)
            .createScanIntent(context)
        ActivityScenario.launch<ScreenCodeCaptureActivity>(intent).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    private fun withPreferences(block: () -> Unit) {
        val prefs = context.getSharedPreferences(ScannerFeature.PREFERENCES, 0)
        val keys = listOf(ScannerFeature.KEY_ENABLED, "laundry_enabled", "water_enabled")
        val previous = keys.associateWith { prefs.all[it] }
        try { block() }
        finally {
            val editor = prefs.edit()
            previous.forEach { (key, value) ->
                if (value is Boolean) editor.putBoolean(key, value) else editor.remove(key)
            }
            editor.commit()
        }
    }
}
