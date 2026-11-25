package dev.rain.laundry

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rain.laundry.cloud.*
import dev.rain.laundry.core.*
import dev.rain.laundry.data.*
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.notifications.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class Page { HOME, DEVICE, CHECKOUT, ORDERS, WATER }
data class UiState(
    val loggedIn: Boolean = false, val phoneLabel: String = "", val page: Page = Page.HOME,
    val busy: Boolean = false, val progress: String = "", val message: String? = null,
    val smsCooldown: Int = 0, val smsPhone: String = "", val device: Device? = null,
    val mode: WashMode? = null, val dryerDuration: DryerDuration? = null,
    val quote: Quote? = null, val methods: List<PayRoute> = emptyList(),
    val payMethod: PayRoute? = null, val order: LocalOrder? = null,
    val orderHistory: List<LocalOrder> = emptyList(),
    val orderFocusKey: String? = null, val orderOpenRequest: Long = 0,
    val laundryProgressError: String? = null,
    val waterDevice: WaterDevice? = null, val waterOutlet: WaterOutlet? = null,
    val waterStatus: WaterStatus? = null, val waterSession: WaterSession? = null,
    val waterPayments: List<WaterPayRoute> = emptyList(), val waterPayment: WaterPayRoute? = null,
    val waterPaymentError: String? = null, val waterUpdateError: String? = null,
    val waterAuthorizationPending: Boolean = false, val waterAuthorizationMessage: String? = null,
    val savedDevices: List<SavedDevice> = emptyList(),
    /** Incremented only after a saved record was verified and its page was applied. */
    val savedDeviceSelection: Long = 0,
    val savedDeviceStatus: SavedDeviceStatus? = null,
    val savedDeviceStatuses: Map<String, SavedDeviceStatus> = emptyMap(),
    val cloudReminder: CloudReminderUiState = CloudReminderUiState(),
    val points: PointsUiState = PointsUiState()
) {
    val waterPaymentAllowed: Boolean get() = waterPayment != null && waterPayment in waterPayments &&
        waterPaymentError == null && !waterAuthorizationPending
}
data class PaymentLaunch(val orderNo: String, val payload: String)
data class WaterAuthorizationLaunch(
    val id: String, val owner: String, val deviceId: String, val routeWire: String,
    val authorization: WaterAlipayAuthorization, val deviceImei: String, val orgId: String,
    val deviceTradeMain: Int? = authorization.tradeMain, val deviceShopId: String = ""
)

class LaundryViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = SessionVault(application)
    private val api = LaundryApi(vault::token, Build.MANUFACTURER)
    private val cloudReminder = CloudReminderManager(application)
    private val location = LocationProvider(application)
    private val _state = MutableStateFlow(UiState(loggedIn = vault.token().isNotEmpty(), phoneLabel = vault.phoneLabel(),
        order = vault.order()?.let { if (it.phase == OrderPhase.CREATING) it.copy(phase = OrderPhase.UNCONFIRMED) else it },
        orderHistory = vault.orders().map(::restoreOrder),
        waterSession = vault.water()?.let { if (it.phase == WaterPhase.STARTING) it.copy(phase = WaterPhase.UNKNOWN) else it },
        savedDevices = vault.savedDevices()))
    val state = _state.asStateFlow()
    private val launchChannel = Channel<PaymentLaunch>(Channel.BUFFERED)
    private val waterAuthorizationChannel = Channel<WaterAuthorizationLaunch>(Channel.BUFFERED)
    private var pendingWaterAuthorization: WaterAuthorizationLaunch? = null
    // One explicit confirmation may continue past an optional prompt after returning, as in the original client.
    // This is not a signed flag; device requirements and the activation response are always checked again.
    private var returnedWaterAuthorization: WaterAuthorizationLaunch? = null
    private var waterAuthorizationVerification: Job? = null
    private var waterRefreshJob: Job? = null
    private var laundryRefreshJob: Job? = null
    private var foregroundRevision = 0L
    private var savedDeviceStatusChecks: Job? = null
    private var savedDeviceStatusGeneration = 0L
    private var smsJob: Job? = null
    private var pointsJob: Job? = null
    private var pointsGeneration = 0L
    val paymentLaunches = launchChannel.receiveAsFlow()
    val waterAuthorizationLaunches = waterAuthorizationChannel.receiveAsFlow()
    private val workflow = OrderWorkflow(api, { unfinishedOrder() ?: _state.value.order }, ::saveOrder)
    private val waterWorkflow = WaterWorkflow(api, { _state.value.waterSession }, ::saveWater)
    private val paymentWorkflow = PaymentWorkflow(
        queryPaid = { order ->
            _state.update { it.copy(progress = "正在查询付款状态") }
            if (!order.isLaundry && OrderStates.apply(order, api.orderStatus(order)).phase in
                setOf(OrderPhase.PAID, OrderPhase.COMPLETED)) true else api.paid(order)
        },
        onPaid = ::confirmPaid,
        startPayment = ::payExisting,
        onBalancePending = { notice("余额付款结果尚未确认，请稍后刷新订单") }
    )
    init {
        viewModelScope.launch {
            cloudReminder.state.collect { cloud -> _state.update { it.copy(cloudReminder = cloud) } }
        }
        if (state.value.loggedIn) {
            if ((state.value.orderHistory + listOfNotNull(state.value.order)).any {
                    it.isLaundry && it.phase == OrderPhase.PAID })
                LaundryNotificationScheduler.requestCheck(getApplication(), vault.owner())
            cloudReminder.onSessionAvailable(vault.owner())
        }
    }
    private fun restoreOrder(order: LocalOrder): LocalOrder =
        if (order.phase == OrderPhase.CREATING) order.copy(phase = OrderPhase.UNCONFIRMED) else order
    private fun unfinishedOrder(): LocalOrder? = state.value.order?.takeIf { !it.phase.finished }
        ?: state.value.orderHistory.firstOrNull { !it.phase.finished }
    private fun saveOrder(order: LocalOrder) {
        if (order.owner != vault.owner()) throw SessionExpired()
        vault.saveOrder(order)
        val history = vault.orders().map(::restoreOrder)
        _state.update { state ->
            val current = state.order
            val updateCurrent = current == null || OrderHistory.sameOrder(current, order) ||
                (current.phase.finished && order.createdAt >= current.createdAt)
            state.copy(order = if (updateCurrent) order else current, orderHistory = history)
        }
        if (order.isLaundry && order.phase == OrderPhase.PAID) {
            LaundryNotificationScheduler.requestCheck(getApplication(), order.owner)
            cloudReminder.onOrderSaved(order.owner)
        }
    }
    /** Viewing history never changes the workflow pointer. Only an explicit action may promote a record. */
    private fun orderForAction(requested: LocalOrder, promote: Boolean = false): LocalOrder? {
        if (state.value.busy) return null
        val owner = vault.owner()
        if (!state.value.loggedIn || owner.isBlank() || requested.owner != owner) {
            notice("请登录订单所属账号后再操作"); return null
        }
        val target = state.value.order?.takeIf { OrderHistory.sameOrder(it, requested) }
            ?: state.value.orderHistory.firstOrNull { OrderHistory.sameOrder(it, requested) }
            ?: run { notice("这笔订单已不在本机记录中"); return null }
        if (promote && !target.phase.finished) {
            val conflict = (state.value.orderHistory + listOfNotNull(state.value.order)).any {
                !it.phase.finished && !OrderHistory.sameOrder(it, target)
            }
            if (conflict) { notice("请先处理当前未完成的订单"); return null }
            try { vault.selectOrder(target) }
            catch (failure: Exception) { notice(failure.message ?: "无法读取本地订单，请稍后重试"); return null }
            _state.update { it.copy(order = target) }
        }
        return target
    }
    private fun saveWater(session: WaterSession) {
        vault.saveWater(session)
        _state.update { it.copy(waterSession = session, waterDevice = session.device, waterOutlet = session.outlet,
            waterStatus = session.lastStatus) }
    }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    fun notice(message: String) { _state.update { it.copy(message = message) } }

    fun refreshPoints() = pointsWork(signIn = false)
    fun autoSignInPoints() = pointsWork(signIn = true)

    private fun pointsWork(signIn: Boolean) {
        if (state.value.busy || pointsJob?.isActive == true) return
        if (!state.value.loggedIn) { notice(appText(R.string.points_login_required)); return }
        val owner = vault.owner().takeIf { it.isNotBlank() } ?: return
        val sessionToken = vault.token().takeIf { it.isNotBlank() } ?: return
        val generation = ++pointsGeneration
        fun current() = state.value.loggedIn && vault.owner() == owner &&
            vault.token() == sessionToken && pointsGeneration == generation
        // Bind every request to this captured session, including while a blocking request unwinds.
        val gateway = PointsApi(LaundryApi({ sessionToken }, Build.MANUFACTURER))
        val workflow = PointsWorkflow(gateway, ::current)
        _state.update { it.copy(points = it.points.copy(loading = !signIn, running = signIn,
            progress = appText(if (signIn) R.string.points_signing else R.string.points_loading),
            error = null, logs = if (signIn) emptyList() else it.points.logs)) }
        pointsJob = viewModelScope.launch {
            try {
                if (signIn) {
                    val changed = workflow.autoSignIn()
                    if (!current()) return@launch
                    val message = appText(if (changed) R.string.points_sign_confirmed else R.string.points_already_signed)
                    _state.update { it.copy(points = it.points.copy(signedInToday = true,
                        progress = appText(R.string.points_loading), logs = listOf(message))) }
                }
                val snapshot = workflow.refresh()
                if (current()) _state.update { it.copy(points = it.points.withSnapshot(snapshot).copy(
                    progress = if (signIn) appText(R.string.points_finished) else "")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionExpired) {
                if (current()) expireLocalSession("登录已过期，请重新登录")
            } catch (error: Exception) {
                if (current()) {
                    val message = when (error) {
                        is PointsResultUnknown -> appText(R.string.points_result_unknown)
                        is BusinessRejected -> error.displayMessage
                        else -> appText(R.string.points_load_error)
                    }
                    _state.update { it.copy(points = it.points.copy(error = message, progress = "")) }
                }
            } finally {
                if (current()) _state.update { it.copy(points = it.points.copy(loading = false, running = false)) }
            }
        }
    }

    private fun clearPointsWork() {
        pointsGeneration++
        pointsJob?.cancel()
        pointsJob = null
    }
    fun openOrderNotification(owner: String, key: String) {
        if (!state.value.loggedIn || vault.owner() != owner) {
            notice("请登录订单所属账号后查看洗衣进度"); return
        }
        val record = (vault.orders() + listOfNotNull(vault.order())).firstOrNull { OrderHistory.key(it) == key }
        _state.update { it.copy(page = Page.ORDERS, orderFocusKey = record?.let(OrderHistory::key),
            orderOpenRequest = it.orderOpenRequest + 1,
            message = if (record == null) "这笔订单已不在最近 20 条记录中" else null) }
    }
    /** Only read queries; no busy banner, scroll reset, payment, or machine commands. */
    fun refreshLaundryProgress() {
        if (state.value.busy || !state.value.loggedIn || laundryRefreshJob?.isActive == true) return
        val owner = vault.owner().takeIf { it.isNotBlank() } ?: return
        val revision = foregroundRevision
        val token = vault.token()
        fun current() = state.value.loggedIn && vault.owner() == owner && vault.token() == token &&
            revision == foregroundRevision && !state.value.busy
        laundryRefreshJob = viewModelScope.launch {
            val result = try { LaundryMonitorEngine.check(getApplication(), owner, api, ::current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SessionExpired) {
                if (current()) expireLocalSession("登录已过期，请重新登录")
                return@launch
            }
            if (!current()) return@launch
            _state.update { it.copy(order = vault.order()?.let(::restoreOrder),
                orderHistory = vault.orders().map(::restoreOrder),
                laundryProgressError = if (result.retry) "洗衣进度暂未同步，将稍后重试" else null) }
            cloudReminder.refreshLocal(owner)
            cloudReminder.requestAutoSync(owner)
            result.nextCheckAtMs?.let { next ->
                LaundryNotificationScheduler.requestCheck(getApplication(), owner,
                    (next - System.currentTimeMillis()).coerceAtLeast(0))
            }
        }
    }
    fun navigate(page: Page) {
        if (state.value.busy) return
        if (!state.value.waterAuthorizationPending && state.value.waterSession?.active != true &&
            (page == Page.WATER || state.value.page == Page.WATER)) {
            clearWaterAuthorization()
            _state.update { it.copy(page = page, waterDevice = null, waterOutlet = null, waterStatus = null,
                waterPayments = emptyList(), waterPayment = null, waterPaymentError = null, waterUpdateError = null) }
        } else _state.update { it.copy(page = page) }
    }
    /** A transaction record is restored only by explicitly opening it, never as a remembered scan target. */
    fun openWaterSession() {
        if (state.value.busy || state.value.waterAuthorizationPending) return
        val session = state.value.waterSession?.takeIf { it.active } ?: return
        _state.update { it.copy(page = Page.WATER, waterDevice = session.device, waterOutlet = session.outlet,
            waterStatus = session.lastStatus, waterPayments = emptyList(), waterPayment = session.payment,
            waterPaymentError = null, waterUpdateError = null) }
    }
    fun selectMode(mode: WashMode) { if (!state.value.busy) _state.update { it.copy(mode = mode,
        dryerDuration = mode.dryerDurations.firstOrNull { duration -> duration.selectedByPlatform } ?: mode.dryerDurations.firstOrNull(), quote = null,
        savedDeviceStatus = it.savedDeviceStatus?.takeUnless { status ->
            status.kind == SavedDeviceKind.LAUNDRY && status.selectionId != mode.id }) } }
    fun selectDryerDuration(duration: DryerDuration) {
        if (!state.value.busy && state.value.device?.isDryer == true && duration in (state.value.mode?.dryerDurations ?: emptyList()))
            _state.update { it.copy(dryerDuration = duration, quote = null) }
    }
    fun selectPayment(method: PayRoute) { if (!state.value.busy && method in state.value.methods) _state.update { it.copy(payMethod = method) } }

    private fun work(progress: String, silent: Boolean = false, task: suspend () -> Unit) {
        if (state.value.busy) return
        foregroundRevision++
        LaundryMonitorEngine.beginForeground()
        _state.update { it.copy(busy = true, progress = progress) }
        viewModelScope.launch {
            try { task() }
            catch (e: CancellationException) { throw e }
            catch (_: SessionExpired) { expireLocalSession("登录已过期，请重新登录") }
            catch (e: QuoteChanged) { _state.update { it.copy(quote = e.quote, message = "价格已更新，请重新确认", page = Page.CHECKOUT) } }
            catch (e: Exception) { val message = when (e) {
                is BusinessRejected -> e.displayMessage
                is ClientError -> e.message.orEmpty()
                else -> "暂时无法处理这份数据，请稍后重试"
            }; if (silent) _state.update { it.copy(waterUpdateError = message) } else notice(message) }
            finally { _state.update { it.copy(busy = false, progress = "") }; LaundryMonitorEngine.endForeground() }
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
            clearWaterAuthorization()
            val previousOwner = vault.owner()
            if (previousOwner.isNotBlank() && previousOwner != WireProtocol.sha256(phone)) {
                cloudReminder.onLogout(previousOwner) { }
            }
            vault.login(phone, token)
            clearPointsWork()
            cancelSavedDeviceStatusChecks()
            smsJob?.cancel()
            val water = vault.water()?.let { if (it.phase == WaterPhase.STARTING) it.copy(phase = WaterPhase.UNKNOWN) else it }
            _state.update { UiState(loggedIn = true, phoneLabel = vault.phoneLabel(), order = vault.order()?.let(::restoreOrder),
                orderHistory = vault.orders().map(::restoreOrder), waterSession = water,
                savedDevices = vault.savedDevices()) }
            cloudReminder.onSessionAvailable(vault.owner())
        }
    }
    fun logout() {
        if (state.value.busy) return
        clearPointsWork()
        cancelSavedDeviceStatusChecks()
        val owner = vault.owner()
        clearWaterAuthorization()
        smsJob?.cancel()
        laundryRefreshJob?.cancel()
        LaundryNotificationScheduler.cancelAll(getApplication())
        // Detach the encrypted old binding before clearing the local session. A failed remote
        // request is retried by WorkManager and never reused for a later account.
        cloudReminder.onLogout(owner) { result ->
            if (result == CloudLogoutResult.UNCONFIRMED && vault.owner().isBlank())
                notice(appText(R.string.cloud_logout_unbind_failed))
        }
        vault.logout()
        _state.value = UiState()
    }

    private fun expireLocalSession(message: String) {
        clearPointsWork()
        cancelSavedDeviceStatusChecks()
        val owner = vault.owner()
        clearWaterAuthorization()
        LaundryNotificationScheduler.cancelAll(getApplication())
        if (owner.isNotBlank()) cloudReminder.onLogout(owner) { }
        vault.logout()
        _state.value = UiState(message = message)
    }

    fun refreshCloudStatus() = cloudReminder.openSettings(vault.owner())
    fun bindCloudReminder(serverUrl: String, pairingCode: String, miaotixingCode: String,
        consent: Boolean
    ) {
        if (!state.value.loggedIn) { notice(appText(R.string.cloud_login_required)); return }
        cloudReminder.bind(vault.owner(), serverUrl, pairingCode, miaotixingCode, consent)
    }
    fun retryCloudSync() = cloudReminder.retrySync(vault.owner())
    fun enableCloudDeviceAssisted() = cloudReminder.enableDeviceAssisted(vault.owner())
    fun unbindCloudReminder() = cloudReminder.unbind(vault.owner())
    fun reauthorizeCloudReminder() = cloudReminder.reauthorize(vault.owner())
    fun updateCloudNotificationCode(code: String) = cloudReminder.updateNotificationCode(vault.owner(), code)
    fun testCloudNotification() = cloudReminder.testNotification(vault.owner())
    fun refreshSavedDevices() {
        if (state.value.busy || !state.value.loggedIn) return
        _state.update { it.copy(savedDevices = vault.savedDevices()) }
    }

    /** Opening laundry history checks its rows only. Water history never starts availability requests. */
    fun checkSavedDeviceStatuses(kind: SavedDeviceKind) {
        cancelSavedDeviceStatusChecks()
        _state.update { it.copy(savedDeviceStatus = null) }
        if (!shouldCheckSavedDeviceStatuses(kind)) return
        val owner = vault.owner()
        val token = vault.token()
        if (!state.value.loggedIn || owner.isBlank() || token.isBlank()) return
        val records = SavedDevices.ofKind(state.value.savedDevices, kind).filter { it.owner == owner }
        val request = SavedDeviceStatusRequest(owner, token, savedDeviceStatusGeneration, kind)
        _state.update { it.copy(savedDeviceStatus = null, savedDeviceStatuses = records.associate {
            SavedDeviceStatus.key(it) to SavedDeviceStatus.checking(it) }) }
        fun current(record: SavedDevice) = state.value.loggedIn && request.accepts(record, vault.owner(), vault.token(),
            savedDeviceStatusGeneration, state.value.savedDevices)
        fun publish(record: SavedDevice, result: SavedDeviceStatus) {
            vault.withLaundrySession(owner, token, { current(record) }) {
                _state.update { snapshot ->
                    if (!snapshot.loggedIn || !request.accepts(record, vault.owner(), vault.token(),
                            savedDeviceStatusGeneration, snapshot.savedDevices)) snapshot
                    else snapshot.copy(savedDeviceStatuses = snapshot.savedDeviceStatuses +
                        (SavedDeviceStatus.key(record) to result))
                }
                true
            }
        }
        savedDeviceStatusChecks = viewModelScope.launch {
            querySavedDeviceStatuses(records, ::current, query = { record ->
                val scanned = api.scanDevice(record.code() ?: throw ClientError(appText(R.string.saved_device_invalid)))
                if (!current(record)) return@querySavedDeviceStatuses null
                if (!SavedDevices.matches(record, scanned))
                    throw ClientError(appText(R.string.saved_device_identity_changed))
                val device = (scanned as? ScannedDevice.Laundry)?.device
                    ?: throw ClientError(appText(R.string.saved_device_identity_changed))
                val mode = device.modes.first()
                val available = api.machineModeAvailable(mode)
                SavedDeviceStatus.laundry(owner, device, mode, available, System.currentTimeMillis())
            }, onResult = { record, result ->
                publish(record, result)
            }, onFailure = { record, failure ->
                if (failure is SessionExpired) {
                    if (current(record)) expireLocalSession(appText(R.string.msg_ff536b7aeffd))
                } else publish(record, SavedDeviceStatus.checking(record).copy(
                        phase = SavedDeviceStatusPhase.ERROR, detail = savedDeviceFailure(failure),
                        checkedAt = System.currentTimeMillis()))
            })
        }
    }

    fun cancelSavedDeviceStatusChecks() {
        savedDeviceStatusGeneration++
        savedDeviceStatusChecks?.cancel()
        savedDeviceStatusChecks = null
        _state.update { it.copy(savedDeviceStatuses = emptyMap()) }
    }

    private fun savedDeviceFailure(failure: Exception): String = when (failure) {
        is BusinessRejected -> failure.displayMessage
        is ClientError, is SessionExpired -> failure.message.orEmpty()
        else -> appText(R.string.saved_status_failed_detail)
    }

    fun deleteSavedDevice(device: SavedDevice) {
        if (state.value.busy) return
        val owner = vault.owner()
        if (!state.value.loggedIn || owner.isBlank() || device.owner != owner) {
            notice(appText(R.string.saved_device_login_required)); return
        }
        try {
            if (vault.deleteSavedDevice(device)) {
                _state.update { it.copy(savedDevices = vault.savedDevices(),
                    savedDeviceStatuses = it.savedDeviceStatuses - SavedDeviceStatus.key(device),
                    savedDeviceStatus = it.savedDeviceStatus?.takeUnless { status -> status.matches(device) }) }
                notice(appText(R.string.saved_device_deleted))
            } else notice(appText(R.string.saved_device_not_found))
        } catch (error: Exception) {
            notice(error.message?.takeIf { it.isNotBlank() } ?: appText(R.string.saved_device_save_failed))
        }
    }

    fun renameSavedDevice(device: SavedDevice, note: String) {
        if (state.value.busy) return
        val owner = vault.owner()
        if (!state.value.loggedIn || owner.isBlank() || device.owner != owner) {
            notice(appText(R.string.saved_device_login_required)); return
        }
        try {
            if (vault.renameSavedDevice(device, note)) {
                _state.update { it.copy(savedDevices = vault.savedDevices()) }
                notice(appText(R.string.saved_device_renamed))
            } else notice(appText(R.string.saved_device_not_found))
        } catch (error: Exception) {
            notice(error.message?.takeIf { it.isNotBlank() } ?: appText(R.string.saved_device_save_failed))
        }
    }

    /** A saved record is only a shortcut to the normal scan; it never supplies price/mode/auth state. */
    fun selectSavedDevice(device: SavedDevice, laundryEnabled: Boolean = true, waterEnabled: Boolean = true,
        dryerEnabled: Boolean = true) {
        if (state.value.busy) return
        if (state.value.waterAuthorizationPending) { notice("请先完成支付宝授权并返回核验"); return }
        val owner = vault.owner()
        if (!state.value.loggedIn || owner.isBlank() || device.owner != owner) {
            notice(appText(R.string.saved_device_login_required)); return
        }
        val code = device.code()
        if (code == null || device.savedKind == null) { notice(appText(R.string.saved_device_invalid)); return }
        if (device.savedKind == SavedDeviceKind.DRYER && !dryerEnabled) {
            notice("烘干功能已关闭，请先在设置中开启"); return
        }
        cancelSavedDeviceStatusChecks()
        selectDeviceCode(code, device, laundryEnabled, waterEnabled, dryerEnabled)
    }

    fun scan(raw: String, laundryEnabled: Boolean = true, waterEnabled: Boolean = true, dryerEnabled: Boolean = true) {
        val code = try { DeviceCodes.parse(raw) } catch (error: ClientError) {
            notice(error.message.orEmpty()); return
        }
        if (!state.value.busy) cancelSavedDeviceStatusChecks()
        selectDeviceCode(code, null, laundryEnabled, waterEnabled, dryerEnabled)
    }

    /**
     * The single path used by a fresh scan and by an explicit saved-device tap. The platform is
     * queried read-only, identity is checked before applying state, and the owner/token/revision
     * are rechecked before writing the record.
     */
    private fun selectDeviceCode(code: DeviceCode, expectedSaved: SavedDevice?,
        laundryEnabled: Boolean, waterEnabled: Boolean, dryerEnabled: Boolean) {
        if (state.value.busy) return
        _state.update { it.copy(savedDeviceStatus = expectedSaved
            ?.takeIf { record -> shouldCheckSavedDeviceStatuses(record.savedKind) }
            ?.let { record -> SavedDeviceStatus.checking(record) }) }
        work("正在识别设备") {
            val owner = vault.owner()
            val token = vault.token()
            if (!state.value.loggedIn || owner.isBlank() || token.isBlank()) throw SessionExpired()
            if (expectedSaved != null && (expectedSaved.owner != owner || expectedSaved.savedKind == null))
                throw ClientError(appText(R.string.saved_device_invalid))
            val revision = foregroundRevision
            fun current() = state.value.loggedIn && vault.owner() == owner && vault.token() == token &&
                foregroundRevision == revision
            try {
                val scanned = queryDeviceForCurrentSession(::current) { api.scanDevice(code) } ?: return@work
                if (expectedSaved != null && !SavedDevices.matches(expectedSaved, scanned)) {
                    throw ClientError(appText(R.string.saved_device_identity_changed))
                }
                when (scanned) {
                    is ScannedDevice.Laundry -> {
                        if (!laundryEnabled) throw ClientError("洗衣功能已关闭，请先在设置中开启")
                        val device = scanned.device
                        val statusSnapshot = if (expectedSaved != null && device.category in setOf("00", "01")) {
                            val mode = device.modes.first()
                            val available = queryCurrentDeviceValue(::current) { api.machineModeAvailable(mode) }
                                ?: return@work
                            SavedDeviceStatus.laundry(owner, device, mode, available, System.currentTimeMillis())
                        } else null
                        val candidate = SavedDevices.fromScan(owner, code, scanned,
                            System.currentTimeMillis())
                        if (!SavedDevices.isValid(owner, candidate))
                            throw ClientError(appText(R.string.saved_device_invalid))
                        val historyWrite = saveOptionalDeviceHistory(::current) {
                            vault.saveSavedDevice(candidate, owner, token, ::current)
                        }
                        if (historyWrite == SavedDeviceWrite.STALE || !current()) return@work
                        val devices = runCatching { vault.savedDevices() }.getOrDefault(state.value.savedDevices)
                        clearWaterAuthorization()
                        _state.update { it.copy(device = device,
                            mode = device.modes.firstOrNull(), dryerDuration = null, quote = null, page = Page.DEVICE,
                            savedDeviceStatus = statusSnapshot,
                            savedDevices = devices,
                            message = if (historyWrite == SavedDeviceWrite.UNAVAILABLE)
                                appText(R.string.saved_device_save_failed) else it.message,
                            savedDeviceSelection = if (expectedSaved != null) it.savedDeviceSelection + 1
                                else it.savedDeviceSelection) }
                    }
                    is ScannedDevice.Dryer -> {
                        if (!dryerEnabled) throw ClientError("烘干功能已关闭，请先在设置中开启")
                        val device = scanned.device
                        if (device.modes.any { it.dryerDurations.isEmpty() || it.dryerDurations.any { d -> d.minutes <= 0 } })
                            throw ClientError("烘干时长尚未完整适配，请在原应用核对")
                        val candidate = SavedDevices.fromScan(owner, code, scanned, System.currentTimeMillis())
                        if (!SavedDevices.isValid(owner, candidate)) throw ClientError(appText(R.string.saved_device_invalid))
                        val historyWrite = saveOptionalDeviceHistory(::current) {
                            vault.saveSavedDevice(candidate, owner, token, ::current)
                        }
                        if (historyWrite == SavedDeviceWrite.STALE || !current()) return@work
                        clearWaterAuthorization()
                        _state.update { it.copy(device = device,
                            mode = device.modes.firstOrNull(),
                            dryerDuration = device.modes.firstOrNull()?.let { m ->
                                m.dryerDurations.firstOrNull { d -> d.selectedByPlatform } ?: m.dryerDurations.firstOrNull()
                            },
                            quote = null, page = Page.DEVICE,
                            savedDevices = runCatching { vault.savedDevices() }.getOrDefault(state.value.savedDevices),
                            savedDeviceStatus = null,
                            message = if (historyWrite == SavedDeviceWrite.UNAVAILABLE) appText(R.string.saved_device_save_failed) else it.message,
                            savedDeviceSelection = if (expectedSaved != null) it.savedDeviceSelection + 1 else it.savedDeviceSelection) }
                    }
                    is ScannedDevice.Water -> {
                        if (!waterEnabled) throw ClientError("饮水功能已关闭，请先在设置中开启")
                        val active = state.value.waterSession?.takeIf { it.active }
                        if (active != null && active.device.id != scanned.device.id) throw ClientError("请先处理正在进行的饮水会话")
                        val outlet = active?.outlet ?: scanned.device.outlets.first()
                        val candidate = SavedDevices.fromScan(owner, code, scanned,
                            System.currentTimeMillis())
                        if (!SavedDevices.isValid(owner, candidate))
                            throw ClientError(appText(R.string.saved_device_invalid))
                        val historyWrite = saveOptionalDeviceHistory(::current) {
                            vault.saveSavedDevice(candidate, owner, token, ::current)
                        }
                        if (historyWrite == SavedDeviceWrite.STALE || !current()) return@work
                        val devices = runCatching { vault.savedDevices() }.getOrDefault(state.value.savedDevices)
                        clearWaterAuthorization()
                        _state.update { it.copy(waterDevice = scanned.device, waterOutlet = outlet, waterStatus = null,
                            waterPayments = emptyList(), waterPayment = null, waterPaymentError = null,
                            waterUpdateError = null, page = Page.WATER, savedDevices = devices,
                            savedDeviceStatus = null,
                            message = if (historyWrite == SavedDeviceWrite.UNAVAILABLE)
                                appText(R.string.saved_device_save_failed) else it.message,
                            savedDeviceSelection = if (expectedSaved != null) it.savedDeviceSelection + 1
                                else it.savedDeviceSelection) }
                        queryCurrentDeviceValue(::current) {
                            loadWaterPayments(scanned.device, ::current)
                            reconcileWater(::current)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (!current()) return@work
                if (expectedSaved != null && shouldCheckSavedDeviceStatuses(expectedSaved.savedKind)) {
                    _state.update {
                        it.copy(savedDeviceStatus = SavedDeviceStatus.checking(expectedSaved).copy(
                            phase = SavedDeviceStatusPhase.ERROR,
                            detail = savedDeviceFailure(failure),
                            checkedAt = System.currentTimeMillis()
                        ))
                    }
                }
                throw failure
            }
        }
    }
    fun selectWaterOutlet(outlet: WaterOutlet) {
        val device = state.value.waterDevice ?: return
        if (state.value.waterSession?.active == true || state.value.waterAuthorizationPending || outlet !in device.outlets) return
        work("正在查询出水口") {
            _state.update { it.copy(waterOutlet = outlet, waterStatus = null, waterUpdateError = null,
                savedDeviceStatus = it.savedDeviceStatus?.takeUnless { status -> status.kind == SavedDeviceKind.WATER }) }
            reconcileWater()
        }
    }
    fun selectWaterPayment(payment: WaterPayRoute) {
        if (!state.value.busy && !state.value.waterAuthorizationPending && state.value.waterSession?.active != true && payment in state.value.waterPayments)
            _state.update { it.copy(waterPayment = payment, waterAuthorizationMessage = null) }
    }
    fun startWater() {
        val device = state.value.waterDevice ?: return
        val outlet = state.value.waterOutlet ?: return
        if (!state.value.loggedIn || vault.owner().isBlank()) { notice("请先登录后使用饮水机"); return }
        if (state.value.waterAuthorizationPending) { notice("请先完成支付宝授权并返回核验"); return }
        if (unfinishedOrder() != null) { notice("请先处理未完成的订单"); return }
        val payment = state.value.waterPayment
        if (!state.value.waterPaymentAllowed || payment == null) { notice("请先刷新并选择饮水支付方式"); return }
        if (!location.permitted()) { notice("开始用水前需要定位，用于确认设备所在位置"); return }
        work("正在启动饮水机") {
            val returned = returnedWaterAuthorization?.takeIf { matchesWaterAuthorization(it, requirePending = false) }
            returnedWaterAuthorization = null
            try {
                waterWorkflow.start(device, outlet, vault.owner(), location.current(), payment, returned?.authorization?.tradeMain)
            } catch (error: WaterStartNotSent) {
                val authorization = error.authorization ?: throw error
                val handoffDevice = state.value.waterDevice ?: throw ClientError("饮水机信息已变化，请重新扫码确认")
                val launch = WaterAuthorizationLaunch(UUID.randomUUID().toString(), vault.owner(), handoffDevice.id,
                    payment.wire, authorization, handoffDevice.imei, handoffDevice.orgId,
                    handoffDevice.tradeMain, handoffDevice.shopId)
                pendingWaterAuthorization = launch
                _state.update { it.copy(waterAuthorizationPending = true,
                    waterAuthorizationMessage = "需要先完成本设备的支付宝免密授权，正在打开支付宝。") }
                waterAuthorizationChannel.send(launch)
                return@work
            }
            _state.update { it.copy(waterAuthorizationMessage = null) }
            notice("启动指令已提交，请等待饮水机响应")
            reconcileWater()
        }
    }

    private fun matchesWaterAuthorization(launch: WaterAuthorizationLaunch, requirePending: Boolean = true): Boolean =
        (!requirePending || pendingWaterAuthorization?.id == launch.id) && state.value.loggedIn && vault.owner() == launch.owner &&
            state.value.waterDevice?.id == launch.deviceId && state.value.waterDevice?.imei == launch.deviceImei &&
            state.value.waterDevice?.orgId == launch.orgId &&
            state.value.waterDevice?.tradeMain == launch.deviceTradeMain &&
            (launch.deviceShopId.isEmpty() || state.value.waterDevice?.shopId == launch.deviceShopId) &&
            state.value.waterPayment?.wire == launch.routeWire &&
            state.value.waterSession?.active != true

    fun canLaunchWaterAuthorization(launch: WaterAuthorizationLaunch): Boolean =
        pendingWaterAuthorization?.id == launch.id && matchesWaterAuthorization(launch) &&
            waterAuthorizationVerification?.isActive != true

    fun discardWaterAuthorization(id: String) {
        if (pendingWaterAuthorization?.id == id) clearWaterAuthorization()
    }

    private fun clearWaterAuthorization() {
        pendingWaterAuthorization = null
        returnedWaterAuthorization = null
        waterAuthorizationVerification?.cancel()
        waterAuthorizationVerification = null
        _state.update { it.copy(waterAuthorizationPending = false, waterAuthorizationMessage = null) }
    }

    /** A bridge return is not proof of signing, and can never start water automatically. */
    fun waterAuthorizationReturned(id: String, bridgeMessage: String? = null) {
        val launch = pendingWaterAuthorization?.takeIf { it.id == id } ?: return
        if (waterAuthorizationVerification?.isActive == true) return
        if (matchesWaterAuthorization(launch)) returnedWaterAuthorization = launch
        waterAuthorizationVerification = viewModelScope.launch {
            var ownsBusy = false
            try {
                // The bridge can fail immediately while the original start task is still unwinding.
                while (state.value.busy) delay(50)
                if (!matchesWaterAuthorization(launch)) return@launch
                val device = state.value.waterDevice ?: return@launch
                _state.update { it.copy(busy = true, progress = "正在核验支付宝免密授权") }
                ownsBusy = true
                val remaining = recheckWaterAlipay(device) { matchesWaterAuthorization(launch) } ?: return@launch
                if (!matchesWaterAuthorization(launch)) return@launch
                val message = if (!remaining.needsSigning) "平台未再要求签约，请重新确认用水。"
                    else listOfNotNull(bridgeMessage, if (remaining.forcedByDevice)
                        "平台仍要求本设备完成免密开通，请稍后刷新核验。"
                        else "账户授权状态尚未同步。可再次确认用水，由平台核对；不会自动开始出水。").joinToString("\n")
                _state.update { it.copy(waterAuthorizationMessage = message) }
                notice(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionExpired) {
                expireLocalSession("登录已过期，请重新登录")
            } catch (error: Exception) {
                if (matchesWaterAuthorization(launch)) {
                    val detail = when (error) {
                        is BusinessRejected -> error.displayMessage
                        is ClientError -> error.message.orEmpty()
                        else -> "暂时无法核验"
                    }
                    val message = "支付宝授权状态尚未确认：$detail。请刷新后重新确认用水。"
                    _state.update { it.copy(waterAuthorizationMessage = message) }
                    notice(message)
                }
            } finally {
                if (pendingWaterAuthorization?.id == launch.id) {
                    pendingWaterAuthorization = null
                    _state.update { it.copy(waterAuthorizationPending = false) }
                }
                if (ownsBusy) _state.update { it.copy(busy = false, progress = "") }
            }
        }
    }

    /** Retry only read-only checks after returning to the app, never selection/unlock or signing launch. */
    private suspend fun recheckWaterAlipay(device: WaterDevice, isCurrent: () -> Boolean): WaterAlipayRequirement? {
        repeat(3) { attempt ->
            if (!isCurrent()) return null
            try {
                val fresh = api.refreshWaterDevice(device)
                if (fresh.id != device.id || fresh.imei != device.imei || fresh.orgId != device.orgId ||
                    fresh.shopId != device.shopId || fresh.tradeMain != device.tradeMain)
                    throw ClientError("饮水机或商户信息已变化，请重新扫码确认")
                if (!isCurrent()) return null
                val requirement = api.waterAlipayRequirement(fresh)
                if (!isCurrent()) return null
                return requirement
            } catch (error: NetworkUnavailable) {
                if (attempt == 2) throw error
                delay(if (attempt == 0) 500 else 1000)
            }
        }
        return null
    }
    fun endWater() {
        work("正在结束用水") {
            waterWorkflow.finish()
            reconcileWater()
        }
    }
    fun refreshWater(silent: Boolean = false) {
        if (state.value.waterDevice == null || state.value.waterAuthorizationPending) return
        if (silent) {
            if (state.value.busy || waterRefreshJob?.isActive == true) return
            val owner = vault.owner()
            val device = state.value.waterDevice
            val outlet = state.value.waterOutlet
            val revision = foregroundRevision
            val isCurrent = { foregroundRevision == revision && !state.value.busy &&
                state.value.loggedIn && vault.owner() == owner && state.value.page == Page.WATER &&
                state.value.waterDevice?.id == device?.id && state.value.waterOutlet?.id == outlet?.id }
            // Polling updates data in place and never toggles the page-wide busy/progress layout.
            waterRefreshJob = viewModelScope.launch {
                try { reconcileWater(isCurrent) }
                catch (error: CancellationException) { throw error }
                catch (_: SessionExpired) {
                    if (isCurrent()) expireLocalSession("登录已过期，请重新登录")
                } catch (error: Exception) {
                    if (isCurrent()) _state.update { it.copy(waterUpdateError = when (error) {
                        is BusinessRejected -> error.displayMessage
                        is ClientError -> error.message.orEmpty()
                        else -> "饮水状态暂时无法刷新，请稍后重试"
                    }) }
                }
            }
            return
        }
        work(if (silent) "" else "正在刷新饮水状态", silent) {
            if (!silent) loadWaterPayments(state.value.waterDevice!!)
            reconcileWater()
            if (!silent && state.value.waterPaymentError == null &&
                state.value.waterSession?.active != true && state.value.waterPayment?.requiresAlipayAgreement == true) {
                val device = state.value.waterDevice ?: return@work
                val owner = vault.owner()
                val isCurrent = { state.value.loggedIn && vault.owner() == owner && state.value.waterDevice == device &&
                    state.value.waterPayment?.requiresAlipayAgreement == true && !state.value.waterAuthorizationPending &&
                    state.value.waterSession?.active != true }
                _state.update { it.copy(waterAuthorizationMessage = null) }
                try {
                    val requirement = recheckWaterAlipay(device, isCurrent) ?: return@work
                    _state.update { it.copy(waterAuthorizationMessage = if (requirement.needsSigning)
                        "平台仍提示本设备需要免密授权，请稍后核验；确认用水后将打开支付宝。"
                        else "平台未再要求签约，请重新确认用水。") }
                } catch (error: CancellationException) { throw error
                } catch (error: SessionExpired) { throw error
                } catch (error: Exception) {
                    if (isCurrent()) _state.update { it.copy(waterAuthorizationMessage =
                        "支付宝授权状态尚未确认，请稍后刷新核验。") }
                    throw error
                }
            } else if (!silent && !state.value.waterAuthorizationPending) {
                _state.update { it.copy(waterAuthorizationMessage = null) }
            }
        }
    }
    private suspend fun loadWaterPayments(device: WaterDevice, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val previous = state.value.waterPayment ?: state.value.waterSession?.takeIf { it.device.id == device.id }?.payment
        _state.update { it.copy(waterPayments = emptyList(), waterPaymentError = null) }
        try {
            val methods = api.waterPaymentRoutes(device)
            if (!isCurrent()) return
            val selected = methods.firstOrNull { it.wire == previous?.wire } ?: methods.firstOrNull { it.preferred }
                ?: methods.firstOrNull { it.method == PayMethod.BALANCE } ?: methods.first()
            _state.update { it.copy(waterPayments = methods, waterPayment = selected) }
        } catch (error: ClientError) {
            if (isCurrent()) _state.update { it.copy(waterPaymentError = error.message) }
        }
    }
    private suspend fun reconcileWater(isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val outlet = state.value.waterOutlet ?: return
        val device = state.value.waterDevice ?: return
        val status = api.waterStatus(outlet)
        if (!isCurrent()) return
        _state.update { it.copy(waterStatus = status, waterUpdateError = null) }
        var session = state.value.waterSession?.takeIf { it.device.id == device.id && it.outlet.id == outlet.id && it.active }
        if (session == null && status.mine && vault.owner().isNotBlank()) {
            session = WaterSession(vault.owner(), device, outlet, WaterPhase.USING, lastStatus = status, recovered = true)
        }
        if (session == null) return
        var current = WaterRules.apply(session, status)
        saveWater(current)
        if (current.phase == WaterPhase.WAITING && current.msgId.isNotBlank()) {
            val accepted = api.waterCommandAccepted(current.msgId)
            if (!isCurrent()) return
            if (accepted) { current = current.copy(msgId = ""); saveWater(current) }
        }
        if (current.phase == WaterPhase.SETTLING) {
            val order = api.waterSettlement(current) ?: return
            if (!isCurrent()) return
            saveOrder(order)
            saveWater(current.copy(phase = WaterPhase.ENDED, amount = order.amount, settlementOrder = order))
            notice(if (order.phase.finished) "饮水已结束，平台已返回结算账单" else "饮水已结束，请在订单中确认并支付账单")
        }
    }
    fun preview() {
        val device = state.value.device ?: return; val mode = state.value.mode ?: return
        work("正在确认价格") {
            val quote = if (device.isDryer) api.quoteDryer(device, mode,
                state.value.dryerDuration ?: throw ClientError("请选择烘干时长")) else api.quote(device, mode)
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
            catch (e: Exception) {
                if (state.value.order?.let { it.phase == OrderPhase.UNCONFIRMED || it.orderId.isNotBlank() || it.orderNo.isNotBlank() } == true)
                    _state.update { it.copy(page = Page.ORDERS) }
                throw e
            }
            _state.update { it.copy(page = Page.ORDERS) }
            paymentWorkflow.startOrResume(order)
        }
    }
    fun resumePayment() { state.value.order?.let(::resumePayment) }
    fun resumePayment(requested: LocalOrder) {
        val order = orderForAction(requested, promote = true) ?: return
        if (order.orderNo.isEmpty() || order.phase.finished) return
        work("正在核对付款状态") {
            val current = synchronizeOrderState(order)
            if (current.phase.finished) noticeOrderState(current)
            else paymentWorkflow.startOrResume(current)
        }
    }
    private suspend fun payExisting(order: LocalOrder) {
        val payable = api.waterBillPaymentRoute(order)
        if (payable != order) saveOrder(payable)
        _state.update { it.copy(progress = if (order.payMethod == PayMethod.BALANCE) "正在提交余额付款" else "正在获取支付信息") }
        if (order.payMethod == PayMethod.BALANCE) {
            val pending = payable.copy(phase = OrderPhase.PAYMENT_PENDING)
            api.balancePay(payable) { saveOrder(pending) }
            poll(pending)
        } else {
            val payload = api.prepay(payable)
            PurchaseRules.verifyAlipay(payload, order.amount)
            saveOrder(payable.copy(phase = OrderPhase.PAYMENT_PENDING))
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
    fun cancelOrder() { state.value.order?.let(::cancelOrder) }
    fun cancelOrder(requested: LocalOrder) {
        val order = orderForAction(requested, promote = true) ?: return
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
    fun refreshOrder() { state.value.order?.let(::refreshOrder) }
    fun refreshOrder(requested: LocalOrder) {
        val order = orderForAction(requested) ?: return
        if (order.orderId.isBlank() && order.orderNo.isBlank()) { notice("上一次下单结果未确认，请先在原应用核对订单"); return }
        work("正在查询订单") { noticeOrderState(synchronizeOrderState(order)) }
    }
    private suspend fun synchronizeOrderState(order: LocalOrder): LocalOrder {
        val details = api.orderDetails(order)
        val current = OrderStates.apply(order, details.status).copy(laundryProgress = details.laundryProgress)
        if (current != order) saveOrder(current)
        LaundryMonitorEngine.observe(getApplication(), current)
        return current
    }
    private fun noticeOrderState(order: LocalOrder) = notice(when (order.phase) {
        OrderPhase.CLOSED -> "订单已关闭，可以返回洗衣重新选择模式"
        OrderPhase.COMPLETED -> "订单已完成"
        OrderPhase.REFUNDING -> "订单正在退款，请在原应用查看进度"
        OrderPhase.REFUNDED -> "订单已退款，请核对到账情况"
        OrderPhase.PAID -> when {
            order.isWater -> "饮水账单付款已确认"
            order.isDryer -> "烘干订单付款已确认，请查看机器状态"
            else -> "付款已确认，请查看洗衣机运行状态"
        }
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
            try {
                api.sync(paid)
                notice(if (paid.isDryer) "付款已确认，已请求启动烘干机，请查看设备状态" else "付款已确认，已请求同步设备，请查看机器是否启动")
            } catch (_: Exception) {
                notice(if (paid.isDryer) "付款已确认；烘干启动结果待核实，请查看设备" else "付款已确认；设备同步结果待核实，请查看洗衣机")
            }
        } else notice(if (paid.isDryer) "付款已确认，请查看烘干机运行状态" else "付款已确认，请查看洗衣机运行状态")
    }
}
