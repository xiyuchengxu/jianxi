package dev.rain.laundry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rain.laundry.*
import dev.rain.laundry.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaundryApp(
    vm: LaundryViewModel,
    onScan: () -> Unit,
    onPay: () -> Unit,
    settings: ThemeSettings = ThemeSettings(),
    onThemeChange: (ThemeSettings) -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    if (aboutOpen) {
        AboutScreen(onBack = { aboutOpen = false })
        return
    }
    val backdrop = rememberGraphicsLayer()
    val snackbar = remember { SnackbarHostState() }
    var inputOpen by rememberSaveable { mutableStateOf(false) }
    var logoutOpen by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val colors = MaterialTheme.colorScheme
    val screen = when {
        themeOpen -> AppScreen.THEME
        !state.loggedIn -> AppScreen.LOGIN
        state.page == Page.HOME -> AppScreen.HOME
        state.page == Page.DEVICE -> AppScreen.DEVICE
        state.page == Page.CHECKOUT -> AppScreen.CHECKOUT
        else -> AppScreen.ORDERS
    }
    val selectedTab = if (themeOpen) 2 else if (state.page == Page.ORDERS) 1 else 0
    val selectTab: (Int) -> Unit = { index ->
        when (index) {
            2 -> if (!themeOpen) themeOpen = true
            0, 1 -> {
                val destination = if (index == 0) Page.HOME else Page.ORDERS
                if (themeOpen || state.page != destination) {
                    themeOpen = false
                    vm.navigate(destination)
                }
            }
        }
    }
    val swipeEnabled = settings.swipeNavigation && state.loggedIn && !state.busy &&
        screen in listOf(AppScreen.HOME, AppScreen.ORDERS, AppScreen.THEME)
    // Scaffold already supplies the system navigation inset; reserve only the bar itself here.
    val navigationSpace = if (!state.loggedIn) 0.dp else if (settings.bottomBar == BottomBarStyle.FLOATING) 96.dp else 80.dp
    val glassUnderlay = state.loggedIn && settings.bottomBar == BottomBarStyle.FLOATING && settings.glassLevel != GlassLevel.NONE
    // Glass samples scrolling content; final items still scroll fully above the controls.
    val scrollBottom = 28.dp + if (glassUnderlay) navigationSpace else 0.dp
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) pageStates.removeState(AppScreen.LOGIN.name)
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            if (vm.state.value.message == message) vm.clearMessage()
        }
    }
    BackHandler(state.loggedIn && !themeOpen && state.page !in listOf(Page.HOME, Page.ORDERS) && !state.busy) {
        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
    }
    BackHandler(themeOpen) { themeOpen = false }
    val backdropModifier = if (glassUnderlay) {
        Modifier.drawWithContent {
            backdrop.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop)
        }
    } else Modifier
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().then(backdropModifier)) {
            Scaffold(containerColor = colors.background, topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (themeOpen) Icons.Outlined.Palette else Icons.Outlined.LocalLaundryService, null, tint = colors.primary)
                        Text(if (themeOpen) "主题" else "简洗", fontWeight = FontWeight.Bold)
                    }
                }, navigationIcon = {
                    if (themeOpen) IconButton(onClick = { themeOpen = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    } else if (state.loggedIn && state.page in listOf(Page.DEVICE, Page.CHECKOUT)) IconButton(onClick = {
                        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
                    }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                }, actions = {
                    if (!state.loggedIn && !themeOpen) IconButton(onClick = { themeOpen = true }) {
                        Icon(Icons.Outlined.Palette, contentDescription = "主题设置")
                    }
                    IconButton(onClick = { aboutOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Info, contentDescription = "关于简洗")
                    }
                    if (state.loggedIn) TextButton(onClick = { logoutOpen = true }, enabled = !state.busy) { Text("退出") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
            }, snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = navigationSpace)) }) { padding ->
                AnimatedContent(targetState = screen,
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                        .padding(bottom = if (glassUnderlay) 0.dp else navigationSpace).imePadding()
                        .pageSwipeNavigation(swipeEnabled, selectedTab, selectTab),
                    transitionSpec = {
                        when (settings.animation) {
                            PageAnimation.SLIDE -> {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(280)) { it / 8 * direction } + fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(tween(220)) { -it / 8 * direction } + fadeOut(tween(150)))
                            }
                            PageAnimation.FADE -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                            PageAnimation.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        }
                    }, label = "pageTransition") { displayed ->
                    pageStates.SaveableStateProvider(displayed.name) {
                        if (displayed == AppScreen.THEME) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                ThemeScreen(settings, onThemeChange,
                                    Modifier.widthIn(max = 568.dp).fillMaxSize().padding(horizontal = 24.dp),
                                    contentBottomPadding = scrollBottom)
                            }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = scrollBottom),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                if (state.busy && displayed != AppScreen.THEME) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(state.progress, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                when (displayed) {
                                    AppScreen.LOGIN -> LoginScreen(state, vm::sendCode, vm::login)
                                    AppScreen.HOME -> HomeScreen(state, onScan, { inputOpen = true }, { vm.navigate(Page.ORDERS) })
                                    AppScreen.DEVICE -> DeviceScreen(state, vm::selectMode, vm::preview)
                                    AppScreen.CHECKOUT -> CheckoutScreen(state, vm::selectPayment, onPay)
                                    AppScreen.ORDERS -> OrdersScreen(state, vm::refreshOrder, vm::resumePayment, vm::cancelOrder)
                                    AppScreen.THEME -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.loggedIn) LaundryNavigationBar(backdrop, selectedTab, selectTab,
            style = settings.bottomBar, modifier = Modifier.align(Alignment.BottomCenter),
            enabled = !state.busy, glassLevel = settings.glassLevel)
    }
    if (inputOpen) {
        var raw by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { inputOpen = false }, icon = { Icon(Icons.Outlined.QrCode, null) },
            title = { Text("粘贴设备码") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("粘贴洗衣机二维码的完整内容。")
                    OutlinedTextField(raw, { raw = it.take(4096) }, label = { Text("二维码内容") }, minLines = 3, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = { inputOpen = false; vm.scan(raw) }, enabled = raw.isNotBlank()) { Text("识别设备") } },
            dismissButton = { TextButton(onClick = { inputOpen = false }) { Text("取消") } })
    }
    if (logoutOpen) AlertDialog(onDismissRequest = { logoutOpen = false }, title = { Text("退出登录？") },
        text = { Text("未完成订单会保留在这台手机，使用同一手机号登录后可继续查看。") },
        confirmButton = { TextButton(onClick = { logoutOpen = false; vm.logout() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { logoutOpen = false }) { Text("取消") } })
}

private enum class AppScreen { LOGIN, HOME, DEVICE, CHECKOUT, ORDERS, THEME }

@Composable
private fun LoginScreen(state: UiState, send: (String) -> Unit, login: (String, String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    HeroIcon(Icons.Outlined.LocalLaundryService)
    Text("把洗衣，\n变简单。", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    Text("登录洗衣账户，开始一次轻松的洗衣。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("手机号登录", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, label = { Text("手机号") },
                prefix = { Text("+86  ") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") },
                singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                trailingIcon = { TextButton(onClick = { send(phone) }, enabled = accepted && !state.busy && phone.length == 11 && state.smsCooldown == 0) {
                    Text(if (state.smsCooldown > 0) "${state.smsCooldown}s" else "获取验证码")
                } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(accepted, { accepted = it }, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("我同意使用此手机号登录本人已有的胖乖生活账户。", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { login(phone, code) }, enabled = accepted && !state.busy && phone.length == 11 && code.length >= 4,
                modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("登录", style = MaterialTheme.typography.titleMedium) }
        }
    }
    Text("简洗是独立客户端。短信和洗衣服务由原平台提供。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HomeScreen(state: UiState, scan: () -> Unit, paste: () -> Unit, orders: () -> Unit) {
    Text("今天，也要干干净净。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(state.phoneLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.LocalLaundryService, null, modifier = Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
            Text("开始洗衣", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("扫描洗衣机上的二维码，\n选择模式后付款。", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("扫一扫", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    OutlinedButton(onClick = paste, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("粘贴设备码")
    }
    state.order?.takeIf { !it.phase.finished }?.let {
        OutlinedCard(onClick = orders, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text("还有一笔订单待确认"); Text("查看付款进度", style = MaterialTheme.typography.bodySmall) }
                Icon(Icons.Outlined.ChevronRight, "查看订单")
            }
        }
    }
}

@Composable
private fun DeviceScreen(state: UiState, select: (WashMode) -> Unit, next: () -> Unit) {
    val device = state.device ?: return
    Text("选择洗衣模式", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    InfoCard(device.name, if (device.remainingMinutes > 0) "使用中 · 约剩 ${device.remainingMinutes} 分钟" else "请确认这是你面前的洗衣机")
    if (device.error.isNotBlank()) Text(device.error, color = MaterialTheme.colorScheme.error)
    device.modes.forEach { mode ->
        OutlinedCard(onClick = { select(mode) }, enabled = !state.busy, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(if (state.mode == mode) 2.dp else 1.dp, if (state.mode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.outlinedCardColors(containerColor = if (state.mode == mode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.mode == mode, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(mode.name, style = MaterialTheme.typography.titleMedium); if (mode.feature.isNotBlank()) Text(mode.feature.take(80), style = MaterialTheme.typography.bodySmall) }
                Text("¥${mode.price}", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    Button(onClick = next, enabled = state.mode != null && !state.busy && device.remainingMinutes <= 0 && device.error.isBlank(),
        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("确认模式，查看金额") }
}

@Composable
private fun CheckoutScreen(state: UiState, choose: (PayRoute) -> Unit, pay: () -> Unit) {
    val quote = state.quote ?: return
    Text("确认这一次洗衣", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    InfoCard(quote.device.name, quote.mode.name)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Line("洗衣项目", quote.mode.name)
            Line("数量", "1 次")
            HorizontalDivider()
            Text("本次实付", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${quote.amount}", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
    Text("支付方式", style = MaterialTheme.typography.titleMedium)
    state.methods.forEach { method ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !state.busy) { choose(method) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(state.payMethod == method, onClick = { choose(method) }, enabled = !state.busy)
            Text(method.title, modifier = Modifier.weight(1f))
            Text("单次付款", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = pay, enabled = !state.busy && state.payMethod != null, modifier = Modifier.fillMaxWidth().height(58.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("确认支付 ¥${quote.amount}", style = MaterialTheme.typography.titleMedium)
    }
    Text("付款前请确认机器内已放好衣物。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrdersScreen(state: UiState, refresh: () -> Unit, resume: () -> Unit, cancel: () -> Unit) {
    Text("我的订单", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    val order = state.order
    if (order == null) {
        Spacer(Modifier.height(28.dp)); HeroIcon(Icons.AutoMirrored.Outlined.ReceiptLong)
        Text("还没有洗衣订单", style = MaterialTheme.typography.titleLarge)
        Text("从底栏进入洗衣，扫描设备即可开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val paid = order.phase == OrderPhase.PAID
    val cancelled = order.phase == OrderPhase.CANCELLED
    val rejected = order.phase == OrderPhase.REJECTED
    val closed = order.phase == OrderPhase.CLOSED
    var cancelDialog by remember { mutableStateOf(false) }
    HeroIcon(if (paid) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule)
    Text(when (order.phase) {
        OrderPhase.PAID -> "付款已确认"
        OrderPhase.CREATING, OrderPhase.UNCONFIRMED -> "下单结果待确认"
        OrderPhase.CREATED -> "订单待付款"
        OrderPhase.PAYMENT_PENDING -> "付款结果待确认"
        OrderPhase.CANCELLED -> "订单已取消"
        OrderPhase.REJECTED -> "下单未成功"
        OrderPhase.CLOSED -> "订单已关闭"
        OrderPhase.COMPLETED -> "订单已完成"
        OrderPhase.REFUNDING -> "退款处理中"
        OrderPhase.REFUNDED -> "订单已退款"
    }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(if (closed) "订单已超时或失效，可以返回洗衣重新选择模式。" else if (order.phase == OrderPhase.COMPLETED) "本次洗衣已结束。" else if (order.phase == OrderPhase.REFUNDING) "平台正在处理退款，请在原应用查看进度。" else if (order.phase == OrderPhase.REFUNDED) "平台已标记退款完成，请核对到账情况。" else if (paid) "请查看洗衣机运行状态。" else if (cancelled || rejected) "可以返回洗衣，重新选择模式。" else if (order.orderNo.isBlank()) "请先在原应用核对这笔订单，避免重复下单。" else if (order.phase == OrderPhase.CREATED) "订单已创建，可以继续付款；若已在原应用付款，请刷新状态。" else "付款结果尚未确认，请先查询订单状态。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Line("设备", order.deviceName); Line("模式", order.modeName); Line("金额", "¥${order.amount}")
            Line("支付方式", order.payMethod.title)
            Line("下单时间", SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(order.createdAt)))
            if (order.orderNo.isNotBlank()) Text("订单号 ${order.orderNo}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (order.orderNo.isNotBlank() && !cancelled) OutlinedButton(onClick = refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("刷新订单状态") }
    if (!order.phase.finished && order.orderNo.isNotBlank() && (order.payMethod == PayMethod.ALIPAY || order.phase == OrderPhase.CREATED))
        Button(onClick = resume, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("继续付款") }
    if (!order.phase.finished && order.orderId.isNotBlank()) TextButton(onClick = { cancelDialog = true }, enabled = !state.busy) { Text("取消未付款订单") }
    Text("这里保留本机最近一笔订单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (cancelDialog) AlertDialog(onDismissRequest = { cancelDialog = false }, title = { Text("取消这笔订单？") },
        text = { Text("会先核对订单详情和付款状态，确认订单仍可取消后再申请。") },
        confirmButton = { TextButton(onClick = { cancelDialog = false; cancel() }) { Text("确认取消") } },
        dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("保留订单") } })
}

@Composable
private fun HeroIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
    }
}
@Composable
private fun InfoCard(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocalLaundryService, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), fontWeight = FontWeight.Medium)
    }
}
