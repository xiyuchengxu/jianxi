package dev.rain.laundry

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.*
import com.alipay.sdk.app.PayTask
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.rain.laundry.ui.*
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.scanner.ScreenCodeCaptureActivity
import dev.rain.laundry.scanner.ScannerFeature
import dev.rain.laundry.payments.WaterAlipayAuthorizationActivity
import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticStatus
import dev.rain.laundry.diagnostics.DiagnosticsLog
import dev.rain.laundry.notifications.LaundryReminders
import kotlinx.coroutines.*

class MainActivity : dev.rain.laundry.i18n.LanguageAwareActivity() {
    private val consent by lazy { ConsentStore(applicationContext) }
    private val vm by lazy { ViewModelProvider(this)[LaundryViewModel::class.java] }
    private var pendingLocationAction: String? = null
    private var pendingWaterAuthorizationId: String? = null
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (consent.isAccepted()) result.contents?.let { raw ->
            val settings = ThemePreferences(this).settings
            if (ScannerFeature.isEnabled(this))
                vm.scan(raw, settings.laundryEnabled, settings.waterEnabled, settings.dryerEnabled)
            else vm.notice(appText(R.string.scanner_feature_disabled))
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (consent.isAccepted()) {
            val action = pendingLocationAction
            pendingLocationAction = null
            if (LocationProvider(this).permitted()) {
                when (action) { "water" -> vm.startWater(); "laundry" -> vm.pay() }
            } else vm.notice(appText(R.string.msg_dfb119a5da45))
        }
    }
    private val waterAuthorization = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestId = result.data?.getStringExtra(WaterAlipayAuthorizationActivity.EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() } ?: pendingWaterAuthorizationId
        if (requestId == pendingWaterAuthorizationId) pendingWaterAuthorizationId = null
        if (consent.isAccepted() && requestId != null) {
            vm.waterAuthorizationReturned(requestId,
                result.data?.getStringExtra(WaterAlipayAuthorizationActivity.EXTRA_MESSAGE))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The renderer chooses its alpha mode on attachment. Reserve the alpha channel before
        // creating the Compose view so later wallpaper tints blend once on every frame.
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        pendingLocationAction = savedInstanceState?.getString("pending_location_action")
        pendingWaterAuthorizationId = savedInstanceState?.getString("pending_water_authorization_id")
        enableEdgeToEdge()
        setContent {
            val appearance = remember { ThemePreferences(this@MainActivity) }
            var accepted by remember { mutableStateOf(consent.isAccepted()) }
            // An Activity recreation (rotation, picker or payment return) must not replay the introduction.
            val splashImage = remember {
                if (savedInstanceState == null && appearance.settings.splashEnabled) appearance.takeNextSplashImage()
                else null
            }
            var showingSplash by remember {
                mutableStateOf(splashImage != null)
            }
            LaundryTheme(if (accepted) appearance.settings else appearance.settings.copy(dynamicColor = false),
                wallpaperBehindStatusBar = accepted && !showingSplash && appearance.settings.wallpaperBackgroundEnabled) {
                if (showingSplash) {
                    SplashScreen(requireNotNull(splashImage), appearance.settings.splashDurationMs,
                        onFinished = { showingSplash = false })
                } else if (!accepted) {
                    FirstLaunchAgreement(consent, onAccepted = { accepted = true }, onExit = { finishAndRemoveTask() })
                } else {
                    val model = remember { vm }
                    LaunchedEffect(model) {
                        model.paymentLaunches.collect { launch ->
                            var status: String? = null
                            try { status = withContext(Dispatchers.IO) { PayTask(this@MainActivity).payV2(launch.payload, true)["resultStatus"] } }
                            catch (_: Exception) { /* Backend status remains authoritative. */ }
                            finally {
                                // The SDK result describes the hand-off. The platform confirms the payment.
                                val callbackStatus = when (status) {
                                    "6001" -> DiagnosticStatus.CANCELLED
                                    "6002" -> DiagnosticStatus.NETWORK_ERROR
                                    "4000", "5000" -> DiagnosticStatus.REJECTED
                                    "9000", "8000", "6004" -> DiagnosticStatus.SUCCESS
                                    else -> DiagnosticStatus.FAILED
                                }
                                val code = status?.takeIf { callbackStatus != DiagnosticStatus.FAILED }?.toIntOrNull()
                                DiagnosticsLog.record(DiagnosticOperation.PAYMENT_SDK_RETURN, callbackStatus, errorCode = code)
                                model.sdkReturned(launch.orderNo, status)
                            }
                        }
                    }
                    LaunchedEffect(model) {
                        model.waterAuthorizationLaunches.collect { launch ->
                            if (!consent.isAccepted() || !model.canLaunchWaterAuthorization(launch)) {
                                model.discardWaterAuthorization(launch.id)
                            } else if (pendingWaterAuthorizationId != launch.id) {
                                pendingWaterAuthorizationId = launch.id
                                try {
                                    waterAuthorization.launch(WaterAlipayAuthorizationActivity.intent(this@MainActivity, launch))
                                } catch (_: Exception) {
                                    pendingWaterAuthorizationId = null
                                    model.waterAuthorizationReturned(launch.id, appText(R.string.msg_8c5635cc9269))
                                }
                            }
                        }
                    }
                    LaundryApp(model, onScan = {
                        if (ScannerFeature.isEnabled(this@MainActivity)) {
                            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(appText(R.string.scanner_title))
                                .setCaptureActivity(ScreenCodeCaptureActivity::class.java)
                                .setBeepEnabled(false).setOrientationLocked(false))
                        } else model.notice(appText(R.string.scanner_feature_disabled))
                    }, onPay = {
                        if (LocationProvider(this).permitted()) model.pay()
                        else {
                            pendingLocationAction = "laundry"
                            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }, onWaterStart = {
                        if (LocationProvider(this).permitted()) model.startWater()
                        else {
                            pendingLocationAction = "water"
                            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }, settings = appearance.settings, onThemeChange = appearance::update)
                }
            }
        }
        if (savedInstanceState == null) handleOrderNotification(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOrderNotification(intent)
    }
    override fun onResume() {
        super.onResume()
        if (consent.isAccepted()) runCatching {
            DiagnosticsLog.record(DiagnosticOperation.APP_FOREGROUND, DiagnosticStatus.SUCCESS)
            dev.rain.laundry.notifications.LaundryLiveMonitor.onAppForeground(this)
        }
    }
    private fun handleOrderNotification(intent: Intent?) {
        if (!consent.isAccepted() || intent?.getBooleanExtra(LaundryReminders.EXTRA_OPEN_ORDERS, false) != true) return
        val owner = intent.getStringExtra(LaundryReminders.EXTRA_OWNER) ?: return
        val key = intent.getStringExtra(LaundryReminders.EXTRA_ORDER_KEY) ?: return
        vm.openOrderNotification(owner, key)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_location_action", pendingLocationAction)
        outState.putString("pending_water_authorization_id", pendingWaterAuthorizationId)
        super.onSaveInstanceState(outState)
    }
}
