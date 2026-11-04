package dev.rain.laundry

import android.Manifest
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
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val consent by lazy { ConsentStore(applicationContext) }
    private val vm by lazy { ViewModelProvider(this)[LaundryViewModel::class.java] }
    private var pendingLocationAction: String? = null
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (consent.isAccepted()) result.contents?.let { raw ->
            val settings = ThemePreferences(this).settings
            vm.scan(raw, settings.laundryEnabled, settings.waterEnabled)
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (consent.isAccepted()) {
            val action = pendingLocationAction
            pendingLocationAction = null
            if (LocationProvider(this).permitted()) {
                when (action) { "water" -> vm.startWater(); "laundry" -> vm.pay() }
            } else vm.notice("允许本次定位后，才能继续操作")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLocationAction = savedInstanceState?.getString("pending_location_action")
        enableEdgeToEdge()
        setContent {
            val appearance = remember { ThemePreferences(this@MainActivity) }
            var accepted by remember { mutableStateOf(consent.isAccepted()) }
            LaundryTheme(if (accepted) appearance.settings else appearance.settings.copy(dynamicColor = false)) {
                if (!accepted) {
                    FirstLaunchAgreement(consent, onAccepted = { accepted = true }, onExit = { finishAndRemoveTask() })
                } else {
                    val model = remember { vm }
                    LaunchedEffect(model) {
                        model.paymentLaunches.collect { launch ->
                            var status: String? = null
                            try { status = withContext(Dispatchers.IO) { PayTask(this@MainActivity).payV2(launch.payload, true)["resultStatus"] } }
                            catch (_: Exception) { /* Backend status remains authoritative. */ }
                            finally { model.sdkReturned(launch.orderNo, status) }
                        }
                    }
                    LaundryApp(model, onScan = {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描洗衣机或饮水机二维码")
                            .setBeepEnabled(false).setOrientationLocked(false))
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
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_location_action", pendingLocationAction)
        super.onSaveInstanceState(outState)
    }
}
