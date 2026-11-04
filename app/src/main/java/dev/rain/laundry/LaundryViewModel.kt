package dev.rain.laundry

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rain.laundry.core.*
import dev.rain.laundry.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class Page { HOME, DEVICE, CHECKOUT, ORDERS, WATER }
data class UiState(
    val loggedIn: Boolean = false, val phoneLabel: String = "", val page: Page = Page.HOME,
    val busy: Boolean = false, val progress: String = "", val message: String? = null,
    val smsCooldown: Int = 0, val smsPhone: String = "", val device: Device? = null,
    val mode: WashMode? = null, val quote: Quote? = null, val methods: List<PayRoute> = emptyList(),
    val payMethod: PayRoute? = null, val order: LocalOrder? = null,
    val waterDevice: WaterDevice? = null, val waterOutlet: WaterOutlet? = null,
    val waterStatus: WaterStatus? = null, val waterSession: WaterSession? = null,
    val waterBalanceAllowed: Boolean = false, val waterUpdateError: String? = null
)
data class PaymentLaunch(val orderNo: String, val payload: String)

class LaundryViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = SessionVault(application)
    private val api = LaundryApi(vault::token, Build.MANUFACTURER)
    private val location = LocationProvider(application)
    private val _state = MutableStateFlow(UiState(loggedIn = vault.token().isNotEmpty(), phoneLabel = vault.phoneLabel(),
        order = vault.order()?.let { if (it.phase == OrderPhase.CREATING) it.copy(phase = OrderPhase.UNCONFIRMED) else it },
        waterSession = vault.water()?.let { if (it.phase == WaterPhase.STARTING) it.copy(phase = WaterPhase.UNKNOWN) else it },
        waterDevice = vault.water()?.device, waterOutlet = vault.water()?.outlet))
    val state = _state.asStateFlow()
    private val launchChannel = Channel<PaymentLaunch>(Channel.BUFFERED)
    private var smsJob: Job? = null
    val paymentLaunches = launchChannel.receiveAsFlow()
    private val workflow = OrderWorkflow(api, { _state.value.order }, ::saveOrder)
    private val waterWorkflow = WaterWorkflow(api, { _state.value.waterSession }, ::saveWater)
    private val paymentWorkflow = PaymentWorkflow(
        queryPaid = { order ->
            _state.update { it.copy(progress = "正在查询付款状态") }
            api.paid(order)
        },
        onPaid = ::confirmPaid,
        startPayment = ::payExisting,
        onBalancePending = { notice("余额付款结果尚未确认，请稍后刷新订单") }
    )
    private fun saveOrder(order: LocalOrder) { vault.saveOrder(order); _state.update { it.copy(order = order) } }
    private fun saveWater(session: WaterSession) {
        vault.saveWater(session)
        _state.update { it.copy(waterSession = session, waterDevice = session.device, waterOutlet = session.outlet,
            waterStatus = session.lastStatus) }
    }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    fun notice(message: String) { _state.update { it.copy(message = message) } }
    fun navigate(page: Page) { if (!state.value.busy) _state.update { it.copy(page = page) } }
    fun selectMode(mode: WashMode) { if (!state.value.busy) _state.update { it.copy(mode = mode, quote = null) } }
    fun selectPayment(method: PayRoute) { if (!state.value.busy && method in state.value.methods) _state.update { it.copy(payMethod = method) } }

    private fun work(progress: String, silent: Boolean = false, task: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true, progress = progress) }
        viewModelScope.launch {
            try { task() }
            catch (e: CancellationException) { throw e }
            catch (_: SessionExpired) { vault.logout(); _state.update { UiState(message = "登录已过期，请重新登录") } }
            catch (e: QuoteChanged) { _state.update { it.copy(quote = e.quote, message = "价格已更新，请重新确认", page = Page.CHECKOUT) } }
            catch (e: Exception) { val message = when (e) {
                is BusinessRejected -> e.displayMessage
                is ClientError -> e.message.orEmpty()
                else -> "暂时无法处理这份数据，请稍后重试"
            }; if (silent) _state.update { it.copy(waterUpdateError = message) } else notice(message) }
            finally { _state.update { it.copy(busy = false, progress = "") } }
        }
    }
    fun sendCode(phone: String) {
        if (!phone.matches(Regex("1[3-9]\\d{9}"))) { notice("请输入正确的手机号"); return }
        if (state.value.smsCooldown > 0) return
        work("正在发送验证码") {
            api.sendCode(phone)
            _state.update { it.copy(smsPhone = phone, smsCooldown = 60, message = "验证码已发送") }
            smsJob?.cancel()
            smsJob = viewModelScope.launch { repeat(60) { delay(1000); _state.update { it.copy(smsCooldown = (it.smsCooldown - 1).coerceAtLeast(0)) } } }
        }
    }
    fun login(phone: String, code: String) {
        if (!phone.matches(Regex("1[3-9]\\d{9}")) || !code.matches(Regex("\\d{4,8}"))) { notice("请填写手机号和短信验证码"); return }
        work("正在登录") {
            val token = api.login(phone, code)
            vault.login(phone, token)
            smsJob?.cancel()
            val water = vault.water()?.let { if (it.phase == WaterPhase.STARTING) it.copy(phase = WaterPhase.UNKNOWN) else it }
            _state.update { UiState(loggedIn = true, phoneLabel = vault.phoneLabel(), order = vault.order(),
                waterSession = water, waterDevice = water?.device, waterOutlet = water?.outlet) }
        }
    }
    fun logout() { if (!state.value.busy) { smsJob?.cancel(); vault.logout(); _state.value = UiState() } }
    fun scan(raw: String, laundryEnabled: Boolean = true, waterEnabled: Boolean = true) {
        work("正在识别设备") {
            when (val scanned = api.scanDevice(DeviceCodes.parse(raw))) {
                is ScannedDevice.Laundry -> {
                    if (!laundryEnabled) throw ClientError("洗衣功能已关闭，请先在设置中开启")
                    _state.update { it.copy(device = scanned.device,
                        mode = scanned.device.modes.firstOrNull(), quote = null, page = Page.DEVICE) }
                }
                is ScannedDevice.Water -> {
                    if (!waterEnabled) throw ClientError("饮水功能已关闭，请先在设置中开启")
                    val active = state.value.waterSession?.takeIf { it.active }
                    if (active != null && active.device.id != scanned.device.id) throw ClientError("请先处理正在进行的饮水会话")
                    val outlet = active?.outlet ?: scanned.device.outlets.first()
                    _state.update { it.copy(waterDevice = scanned.device, waterOutlet = outlet, waterStatus = null,
                        waterBalanceAllowed = false, waterUpdateError = null, page = Page.WATER) }
                    reconcileWater()
                    try {
                        api.checkWaterBalanceChannel(scanned.device)
                        _state.update { it.copy(waterBalanceAllowed = true) }
                    } catch (error: ClientError) { _state.update { it.copy(waterUpdateError = error.message) } }
                }
            }
        }
    }
    fun selectWaterOutlet(outlet: WaterOutlet) {
        val device = state.value.waterDevice ?: return
        if (state.value.waterSession?.active == true || outlet !in device.outlets) return
        work("正在查询出水口") {
            _state.update { it.copy(waterOutlet = outlet, waterStatus = null, waterUpdateError = null) }
            reconcileWater()
        }
    }
    fun startWater() {
        val device = state.value.waterDevice ?: return
        val outlet = state.value.waterOutlet ?: return
        if (!state.value.loggedIn || vault.owner().isBlank()) { notice("请先登录后使用饮水机"); return }
        if (state.value.order?.phase?.finished == false) { notice("请先处理未完成的订单"); return }
        if (!state.value.waterBalanceAllowed) { notice("请先刷新并确认饮水机的余额支付方式"); return }
        if (!location.permitted()) { notice("开始用水前需要定位，用于确认设备所在位置"); return }
        work("正在启动饮水机") {
            waterWorkflow.start(device, outlet, vault.owner(), location.current())
            notice("启动指令已提交，请等待饮水机响应")
            reconcileWater()
        }
    }
    fun endWater() {
        work("正在结束用水") {
            waterWorkflow.finish()
            reconcileWater()
        }
    }
    fun refreshWater(silent: Boolean = false) {
        if (state.value.waterDevice == null) return
        work(if (silent) "" else "正在刷新饮水状态", silent) {
            reconcileWater()
            if (!silent) {
                api.checkWaterBalanceChannel(state.value.waterDevice!!)
                _state.update { it.copy(waterBalanceAllowed = true) }
            }
        }
    }
    private suspend fun reconcileWater() {
        val outlet = state.value.waterOutlet ?: return
        val device = state.value.waterDevice ?: return
        val status = api.waterStatus(outlet)
        _state.update { it.copy(waterStatus = status, waterUpdateError = null) }
        var session = state.value.waterSession?.takeIf { it.device.id == device.id && it.outlet.id == outlet.id && it.active }
        if (session == null && status.mine && vault.owner().isNotBlank()) {
            session = WaterSession(vault.owner(), device, outlet, WaterPhase.USING, lastStatus = status)
        }
        if (session == null) return
        var current = WaterRules.apply(session, status)
        saveWater(current)
        if (current.phase == WaterPhase.WAITING && current.msgId.isNotBlank() && api.waterCommandAccepted(current.msgId)) {
            current = current.copy(msgId = ""); saveWater(current)
        }
        if (current.phase == WaterPhase.SETTLING) {
            val order = api.waterSettlement(current) ?: return
            saveOrder(order)
            saveWater(current.copy(phase = WaterPhase.ENDED, amount = order.amount, settlementOrder = order))
            notice(if (order.phase.finished) "饮水已结束，平台已返回结算账单" else "饮水已结束，请在订单中确认并支付账单")
        }
    }
    fun preview() {
        val device = state.value.device ?: return; val mode = state.value.mode ?: return
        work("正在确认价格") {
            val quote = api.quote(device, mode)
            val methods = api.channels(quote)
            _state.update { it.copy(quote = quote, methods = methods, payMethod = methods.first(), page = Page.CHECKOUT) }
        }
    }
    fun pay() {
        if (state.value.waterSession?.active == true) { notice("请先结束并确认本次饮水，再创建新的订单"); return }
        val quote = state.value.quote ?: return; val method = state.value.payMethod ?: return
        if (!location.permitted()) { notice("付款前需要定位，用于确认设备所在位置"); return }
        work("正在核对订单") {
            val fix = location.current()
            val order = try { workflow.create(quote, method.method, fix, vault.owner(), route = method) }
            catch (e: Exception) { if (state.value.order?.phase == OrderPhase.UNCONFIRMED) _state.update { it.copy(page = Page.ORDERS) }; throw e }
            _state.update { it.copy(page = Page.ORDERS) }
            paymentWorkflow.startOrResume(order)
        }
    }
    fun resumePayment() {
        val order = state.value.order ?: return
        if (order.orderNo.isEmpty() || order.phase.finished) return
        work("正在核对付款状态") {
            val current = synchronizeOrderState(order)
            if (current.phase.finished) noticeOrderState(current)
            else paymentWorkflow.startOrResume(current)
        }
    }
    private suspend fun payExisting(order: LocalOrder) {
        _state.update { it.copy(progress = if (order.payMethod == PayMethod.BALANCE) "正在提交余额付款" else "正在获取支付信息") }
        if (order.payMethod == PayMethod.BALANCE) {
            val pending = order.copy(phase = OrderPhase.PAYMENT_PENDING); saveOrder(pending)
            api.balancePay(pending)
            poll(pending)
        } else {
            val payload = api.prepay(order)
            PurchaseRules.verifyAlipay(payload, order.amount)
            saveOrder(order.copy(phase = OrderPhase.PAYMENT_PENDING))
            launchChannel.send(PaymentLaunch(order.orderNo, payload))
        }
    }
    fun sdkReturned(orderNo: String, sdkStatus: String?) {
        viewModelScope.launch {
            state.first { !it.busy }
            val order = state.value.order?.takeIf { it.orderNo == orderNo && !it.phase.finished } ?: return@launch
            work("正在查询付款结果") {
                if (sdkStatus == "6001") notice("付款已取消，可在订单中继续付款")
                poll(order)
            }
        }
    }
    fun cancelOrder() {
        val order = state.value.order ?: return
        if (order.isWater) { notice("这是已用水的结算账单，请核对金额后支付"); return }
        if (order.orderId.isBlank() || order.phase.finished) return
        work("正在取消订单") {
            val current = synchronizeOrderState(order)
            if (current.phase.finished) { noticeOrderState(current); return@work }
            if (api.paid(current)) { confirmPaid(current); return@work }
            try { api.cancel(current) }
            catch (error: BusinessRejected) {
                // The order can expire between the detail query and cancellation.
                if (error.code == 3122) {
                    val latest = try { synchronizeOrderState(current) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw error }
                    if (latest.phase.finished) { noticeOrderState(latest); return@work }
                }
                throw error
            }
            saveOrder(current.copy(phase = OrderPhase.CANCELLED))
            notice("订单已取消")
        }
    }
    fun refreshOrder() {
        val order = state.value.order ?: return
        if (order.orderId.isBlank() && order.orderNo.isBlank()) { notice("上一次下单结果未确认，请先在原应用核对订单"); return }
        work("正在查询订单") { noticeOrderState(synchronizeOrderState(order)) }
    }
    private suspend fun synchronizeOrderState(order: LocalOrder): LocalOrder {
        val current = OrderStates.apply(order, api.orderStatus(order))
        if (current != order) saveOrder(current)
        return current
    }
    private fun noticeOrderState(order: LocalOrder) = notice(when (order.phase) {
        OrderPhase.CLOSED -> "订单已关闭，可以返回洗衣重新选择模式"
        OrderPhase.COMPLETED -> "订单已完成"
        OrderPhase.REFUNDING -> "订单正在退款，请在原应用查看进度"
        OrderPhase.REFUNDED -> "订单已退款，请核对到账情况"
        OrderPhase.PAID -> if (order.isWater) "饮水账单付款已确认" else "付款已确认，请查看洗衣机运行状态"
        OrderPhase.CANCELLED -> "订单已取消"
        else -> "尚未确认付款，可稍后再次查询"
    })
    private suspend fun poll(order: LocalOrder) {
        repeat(5) { index ->
            if (api.paid(order)) { confirmPaid(order); return }
            if (index < 4) delay(2000)
        }
        val current = synchronizeOrderState(order)
        if (current.phase.finished) noticeOrderState(current)
        else notice("付款结果仍在确认，请稍后刷新订单")
    }
    private suspend fun confirmPaid(order: LocalOrder) {
        val paid = order.copy(phase = OrderPhase.PAID)
        saveOrder(paid)
        if (paid.isWater) { saveOrder(paid.copy(syncRequested = true)); notice("饮水账单付款已确认"); return }
        if (!paid.syncRequested) {
            saveOrder(paid.copy(syncRequested = true))
            try { api.sync(paid); notice("付款已确认，已请求同步设备，请查看机器是否启动") }
            catch (_: Exception) { notice("付款已确认；设备同步结果待核实，请查看洗衣机") }
        } else notice("付款已确认，请查看洗衣机运行状态")
    }
}
