package dev.rain.laundry.payments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.alipay.sdk.app.OpenAuthTask
import dev.rain.laundry.WaterAuthorizationLaunch
import dev.rain.laundry.core.WaterAlipaySigningTarget
import dev.rain.laundry.core.parseWaterAlipaySigningTarget
import dev.rain.laundry.data.ConsentStore
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.ThemePreferences

/** Returns only that the authorization UI ended. The ViewModel must query the merchant again. */
class WaterAlipayAuthorizationActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_ID = "water_authorization_request_id"
        const val EXTRA_MESSAGE = "water_authorization_message"
        private const val EXTRA_URL = "water_authorization_url"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val CALLBACK_SCHEME = "jianxi-alipay-deduct"

        fun intent(context: Context, launch: WaterAuthorizationLaunch): Intent =
            Intent(context, WaterAlipayAuthorizationActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, launch.id)
                .putExtra(EXTRA_URL, launch.authorization.url)
    }

    private var launched = false
    private var leftForAuthorization = false
    private var completed = false
    private var task: OpenAuthTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("authorization_launched") == true
        leftForAuthorization = savedInstanceState?.getBoolean("authorization_left") == true
        // Every result, including cancellation and restoration, carries the request identity.
        setResult(RESULT_CANCELED, resultData())
        if (!ConsentStore(applicationContext).isAccepted()) {
            finishForRecheck("请先阅读并确认使用协议。")
            return
        }
        if (getIntent().getStringExtra(EXTRA_REQUEST_ID).isNullOrBlank()) {
            finishForRecheck("授权请求已失效，请重新确认用水。")
            return
        }
        if (savedInstanceState != null && launched) {
            // A restored bridge never repeats the handoff or waits for a callback owned by the old instance.
            finishForRecheck()
            return
        }
        enableEdgeToEdge()
        setContent {
            val appearance = remember { ThemePreferences(applicationContext) }
            LaundryTheme(appearance.settings) {
                BackHandler { finishForRecheck() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("支付宝免密授权", style = MaterialTheme.typography.headlineSmall)
                        Text("请在支付宝中核对并完成签约。返回后会重新查询本设备的签约要求，不会自动开始出水。",
                            style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { finishForRecheck() }) { Text("返回并核验授权") }
                    }
                }
            }
        }
        if (!launched) window.decorView.post {
            if (!isFinishing && !isDestroyed) launchAuthorization()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchAuthorization() {
        if (launched || completed) return
        launched = true
        try {
            packageManager.getPackageInfo(ALIPAY_PACKAGE, 0)
            when (val target = parseWaterAlipaySigningTarget(getIntent().getStringExtra(EXTRA_URL).orEmpty())) {
                is WaterAlipaySigningTarget.Scheme -> startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).setPackage(ALIPAY_PACKAGE))
                is WaterAlipaySigningTarget.Sdk -> {
                    task = OpenAuthTask(this)
                    task?.execute(CALLBACK_SCHEME, OpenAuthTask.BizType.Deduct,
                        mapOf("sign_params" to target.parameters),
                        OpenAuthTask.Callback { _, _, _ ->
                            // Even SDK OK/9000 is not a contract-status verdict.
                            runOnUiThread {
                                if (!isDestroyed) finishForRecheck()
                            }
                        }, true)
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            finishForRecheck("未安装支付宝，请安装后重新确认用水。")
        } catch (_: ActivityNotFoundException) {
            finishForRecheck("当前支付宝无法打开此授权，请更新支付宝后重试。")
        } catch (_: Exception) {
            finishForRecheck("无法打开本设备的支付宝授权，请返回后重试。")
        }
    }

    override fun onPause() {
        if (launched) leftForAuthorization = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (launched && leftForAuthorization) {
            // Scheme returns and callbacks lost to Activity recreation still end the bridge.
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    finishForRecheck()
                }
            }, 250)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("authorization_launched", launched)
        outState.putBoolean("authorization_left", leftForAuthorization)
        super.onSaveInstanceState(outState)
    }

    private fun resultData(message: String? = null): Intent = Intent()
        .putExtra(EXTRA_REQUEST_ID, getIntent().getStringExtra(EXTRA_REQUEST_ID))
        .apply { if (message != null) putExtra(EXTRA_MESSAGE, message) }

    private fun finishForRecheck(message: String? = null) {
        if (completed || isFinishing || isDestroyed) return
        completed = true
        // Deliberately never report RESULT_OK as a proxy for successful signing.
        setResult(RESULT_CANCELED, resultData(message))
        finish()
    }
}
