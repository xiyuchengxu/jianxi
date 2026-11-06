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
import androidx.compose.runtime.saveable.Saver
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    onWaterStart: () -> Unit = { vm.startWater() },
    settings: ThemeSettings = ThemeSettings(),
    onThemeChange: (ThemeSettings) -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    // One ScrollState per page, hoisted above AnimatedContent. Refreshes and screen
    // recompositions reuse these instances, so each page keeps its scroll offset.
    val pageScrollStates = rememberSaveable(saver = PageScrollStatesSaver) {
        AppScreen.entries.associateWith { ScrollState(0) }
    }
    if (aboutOpen) {
        AboutScreen(onBack = { aboutOpen = false })
        return
    }
    val backdrop = rememberGraphicsLayer()
    val snackbar = remember { SnackbarHostState() }
    var inputOpen by rememberSaveable { mutableStateOf(false) }
    var logoutOpen by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val tabs = remember(settings.laundryEnabled, settings.waterEnabled) { settings.visibleTabs }
    val defaultPage = when { settings.laundryEnabled -> Page.HOME; settings.waterEnabled -> Page.WATER; else -> Page.ORDERS }
    val visiblePage = when {
        !settings.laundryEnabled && state.page in listOf(Page.HOME, Page.DEVICE, Page.CHECKOUT) -> defaultPage
        !settings.waterEnabled && state.page == Page.WATER -> defaultPage
        else -> state.page
    }
    LaunchedEffect(state.loggedIn, state.page, state.busy, visiblePage) {
        if (state.loggedIn && !state.busy && state.page != visiblePage) vm.navigate(visiblePage)
    }
    val screen = when {
        themeOpen -> AppScreen.THEME
        settingsOpen -> AppScreen.SETTINGS
        !state.loggedIn -> AppScreen.LOGIN
        visiblePage == Page.HOME -> AppScreen.HOME
        visiblePage == Page.DEVICE -> AppScreen.DEVICE
        visiblePage == Page.CHECKOUT -> AppScreen.CHECKOUT
        visiblePage == Page.WATER -> AppScreen.WATER
        else -> AppScreen.ORDERS
    }
    val currentTab = when {
        themeOpen || settingsOpen -> NavigationTab.SETTINGS
        visiblePage == Page.WATER -> NavigationTab.WATER
        visiblePage == Page.ORDERS -> NavigationTab.ORDERS
        else -> NavigationTab.LAUNDRY
    }
    val selectedTab = tabs.indexOf(currentTab).coerceAtLeast(0)
    val selectTab: (Int) -> Unit = { index ->
        if (!state.busy) when (val tab = tabs.getOrNull(index)) {
            NavigationTab.SETTINGS -> { settingsOpen = true; themeOpen = false }
            NavigationTab.LAUNDRY, NavigationTab.WATER, NavigationTab.ORDERS -> {
                val destination = when (tab) { NavigationTab.LAUNDRY -> Page.HOME; NavigationTab.WATER -> Page.WATER; else -> Page.ORDERS }
                if (settingsOpen || themeOpen || state.page != destination) {
                    settingsOpen = false
                    themeOpen = false
                    vm.navigate(destination)
                }
            }
            null -> Unit
        }
    }
    val openWater: () -> Unit = {
        if (settings.waterEnabled) {
            settingsOpen = false; themeOpen = false
            // Reopen the stored transaction explicitly; plain navigation after this
            // change no longer restores an idle outlet/device from the vault.
            if (state.waterSession != null) vm.openWaterSession() else vm.navigate(Page.WATER)
        } else {
            settingsOpen = true; themeOpen = false
            vm.notice("请先开启饮水入口，再查看饮水记录")
        }
    }
    val swipeEnabled = settings.swipeNavigation && state.loggedIn && !state.busy &&
        screen in listOf(AppScreen.HOME, AppScreen.WATER, AppScreen.ORDERS, AppScreen.SETTINGS)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, screen, state.waterSession?.startedAt, state.waterSession?.active) {
        if (screen == AppScreen.WATER && state.waterSession?.active == true) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { delay(3000); vm.refreshWater(silent = true) }
            }
        }
    }
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
    BackHandler(state.loggedIn && !settingsOpen && !themeOpen && state.page !in listOf(Page.HOME, Page.WATER, Page.ORDERS) && !state.busy) {
        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
    }
    BackHandler(!state.loggedIn && settingsOpen && !themeOpen) { settingsOpen = false }
    BackHandler(themeOpen) { themeOpen = false; settingsOpen = true }
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
                        Icon(when { themeOpen -> Icons.Outlined.Palette; settingsOpen -> Icons.Outlined.Settings; visiblePage == Page.WATER -> Icons.Outlined.WaterDrop;
                            else -> Icons.Outlined.LocalLaundryService }, null, tint = colors.primary)
                        Text(when { themeOpen -> "主题"; settingsOpen -> "设置"; visiblePage == Page.WATER -> "饮水"; else -> "简洗" }, fontWeight = FontWeight.Bold)
                    }
                }, navigationIcon = {
                    if (themeOpen) IconButton(onClick = { themeOpen = false; settingsOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    } else if (settingsOpen && !state.loggedIn) IconButton(onClick = { settingsOpen = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    } else if (!settingsOpen && state.loggedIn && state.page in listOf(Page.DEVICE, Page.CHECKOUT)) IconButton(onClick = {
                        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
                    }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                }, actions = {
                    if (!state.loggedIn && !themeOpen && !settingsOpen) IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { aboutOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Info, contentDescription = "关于简洗")
                    }
                    if (state.loggedIn) TextButton(onClick = { logoutOpen = true }, enabled = !state.busy) { Text("退出") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
            }, snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = navigationSpace)) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    AnimatedContent(targetState = screen,
                        modifier = Modifier.fillMaxSize()
                            .padding(bottom = if (glassUnderlay) 0.dp else navigationSpace).imePadding()
                            .pageSwipeNavigation(swipeEnabled, selectedTab, tabs.size, selectTab),
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
                            } else Column(Modifier.fillMaxSize()
                                .verticalScroll(pageScrollStates.getValue(displayed))
                                .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = scrollBottom),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                    when (displayed) {
                                        AppScreen.LOGIN -> LoginScreen(state, vm::sendCode, vm::login)
                                        AppScreen.HOME -> HomeScreen(state, onScan, { inputOpen = true }, { vm.navigate(Page.ORDERS) })
                                        AppScreen.SETTINGS -> SettingsScreen(settings, onThemeChange, { themeOpen = true }, !state.busy)
                                        AppScreen.WATER -> WaterScreen(state, onScan, { inputOpen = true }, vm::selectWaterOutlet,
                                            onWaterStart, vm::endWater, { vm.refreshWater() }, { vm.navigate(Page.ORDERS) }, vm::selectWaterPayment)
                                        AppScreen.DEVICE -> DeviceScreen(state, vm::selectMode, vm::preview)
                                        AppScreen.CHECKOUT -> CheckoutScreen(state, vm::selectPayment, onPay)
                                        AppScreen.ORDERS -> OrdersScreen(state, vm::refreshOrder, vm::resumePayment, vm::cancelOrder, openWater)
                                        AppScreen.THEME -> Unit
                                    }
                                }
                            }
                        }
                    }
                    // Progress is an overlay: the scrollable page tree below is never replaced or
                    // restructured when busy/progress changes or a water refresh updates state.
                    if (state.busy && state.progress.isNotBlank() && screen != AppScreen.THEME) {
                        BusyBanner(state.progress, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
        if (state.loggedIn) LaundryNavigationBar(backdrop, selectedTab, selectTab,
            style = settings.bottomBar, modifier = Modifier.align(Alignment.BottomCenter),
            enabled = !state.busy, glassLevel = settings.glassLevel, tabs = tabs)
    }
    if (inputOpen) {
        var raw by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { inputOpen = false }, icon = { Icon(Icons.Outlined.QrCode, null) },
            title = { Text("粘贴设备码") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("粘贴洗衣机或饮水机二维码的完整内容。")
                    OutlinedTextField(raw, { raw = it.take(4096) }, label = { Text("二维码内容") }, minLines = 3, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = {
                inputOpen = false; vm.scan(raw, settings.laundryEnabled, settings.waterEnabled)
            }, enabled = raw.isNotBlank()) { Text("识别设备") } },
            dismissButton = { TextButton(onClick = { inputOpen = false }) { Text("取消") } })
    }
    if (logoutOpen) AlertDialog(onDismissRequest = { logoutOpen = false }, title = { Text("退出登录？") },
        text = { Text("未完成订单会保留在这台手机，使用同一手机号登录后可继续查看。") },
        confirmButton = { TextButton(onClick = { logoutOpen = false; vm.logout() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { logoutOpen = false }) { Text("取消") } })
}

private enum class AppScreen { LOGIN, HOME, DEVICE, CHECKOUT, WATER, ORDERS, SETTINGS, THEME }

private val PageScrollStatesSaver = Saver<Map<AppScreen, ScrollState>, Map<String, Int>>(
    save = { states -> AppScreen.entries.associate { it.name to (states[it]?.value ?: 0) } },
    restore = { saved -> AppScreen.entries.associateWith { ScrollState(saved[it.name] ?: 0) } }
)

@Composable
private fun BusyBanner(progress: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoginScreen(state: UiState, send: (String) -> Unit, login: (String, String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    HeroIcon(Icons.Outlined.LocalLaundryService)
    Text("把洗衣，\n变简单。", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    Text("登录平台账户，使用洗衣和饮水服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Text("简洗是独立客户端。短信、洗衣和饮水服务由原平台提供。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private enum class OrderGroup(val label: String) { ALL("全部"), LAUNDRY("洗衣"), WATER("饮水") }

@Composable
private fun OrdersScreen(state: UiState, refresh: () -> Unit, resume: () -> Unit, cancel: () -> Unit, water: () -> Unit) {
    var group by rememberSaveable { mutableStateOf(OrderGroup.ALL) }
    var cancelDialog by remember { mutableStateOf(false) }
    val order = state.order
    val session = state.waterSession
    val laundryOrder = order?.takeIf { !it.isWater }
    val waterOrder = order?.takeIf { it.isWater }
    val hasLaundry = laundryOrder != null
    val hasWater = session != null || waterOrder != null

    Text("我的订单", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    // A saveable business filter keeps the selected grouping while the page and its
    // scroll position are retained; both sections are shown by default.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderGroup.entries.forEach { candidate ->
            FilterChip(selected = group == candidate, onClick = { group = candidate },
                label = { Text(candidate.label) }, enabled = !state.busy)
        }
    }
    Text("按类型查看本机最近的订单和用水记录。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (!hasLaundry && !hasWater) {
        Spacer(Modifier.height(20.dp)); HeroIcon(Icons.AutoMirrored.Outlined.ReceiptLong)
        Text(when (group) {
            OrderGroup.WATER -> "还没有饮水记录"
            OrderGroup.LAUNDRY -> "还没有洗衣订单"
            OrderGroup.ALL -> "还没有订单"
        }, style = MaterialTheme.typography.titleLarge)
        Text("在这里查看本机最近订单、用水记录和付款进度。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    if (group != OrderGroup.WATER) {
        OrderGroupHeader(Icons.Outlined.LocalLaundryService, "洗衣", if (hasLaundry) "最近一笔" else "暂无记录")
        if (laundryOrder != null) OrderDetail(laundryOrder, state.busy, refresh, resume) { cancelDialog = true }
        else Text("本机暂无可显示的洗衣订单；扫描洗衣机后，最近一笔洗衣订单会显示在这里。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (group != OrderGroup.LAUNDRY) {
        OrderGroupHeader(Icons.Outlined.WaterDrop, "饮水", if (hasWater) "用水记录与账单" else "暂无记录")
        // Session and settlement bill are separate available records; show both as water records.
        if (session != null) WaterSessionEntry(session, water)
        if (waterOrder != null) OrderDetail(waterOrder, state.busy, refresh, resume) { cancelDialog = true }
        if (!hasWater) Text("本机暂无可显示的饮水记录；完成一次饮水后，用水记录和结算账单会显示在这里。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Text("本机保留最近一笔订单和最近一次饮水记录。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (cancelDialog && laundryOrder != null) AlertDialog(onDismissRequest = { cancelDialog = false }, title = { Text("取消这笔订单？") },
        text = { Text("会先核对订单详情和付款状态，确认订单仍可取消后再申请。") },
        confirmButton = { TextButton(onClick = { cancelDialog = false; cancel() }) { Text("确认取消") } },
        dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("保留订单") } })
}

@Composable
private fun OrderGroupHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaterSessionEntry(session: WaterSession, open: () -> Unit) {
    OutlinedCard(onClick = open, enabled = session.active, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (session.active) "饮水进行中 · 查看或结束" else "最近一次饮水记录")
                Text(session.device.name + " · " + session.outlet.name, style = MaterialTheme.typography.bodySmall)
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(session.startedAt)) +
                    (if (session.amount.isNotBlank()) " · ¥${session.amount}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.active) Icon(Icons.Outlined.ChevronRight, "查看饮水记录")
        }
    }
}

@Composable
private fun OrderDetail(order: LocalOrder, busy: Boolean, refresh: () -> Unit, resume: () -> Unit, requestCancel: () -> Unit) {
    val paid = order.phase == OrderPhase.PAID
    val cancelled = order.phase == OrderPhase.CANCELLED
    HeroIcon(if (paid) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule)
    Text(orderHeadline(order), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(orderDescription(order), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Line("设备", order.deviceName)
            Line(if (order.isWater) "出水口" else "模式", order.modeName)
            Line("金额", "¥${order.amount}")
            Line("支付方式", order.payMethod.title)
            Line(if (order.isWater) "账单时间" else "下单时间", SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(order.createdAt)))
            if (order.orderNo.isNotBlank()) Text("订单号 ${order.orderNo}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (order.orderNo.isNotBlank() && !cancelled) OutlinedButton(onClick = refresh, enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("刷新订单状态") }
    if (!order.phase.finished && order.orderNo.isNotBlank() && (order.payMethod == PayMethod.ALIPAY || order.phase == OrderPhase.CREATED))
        Button(onClick = resume, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("继续付款") }
    if (!order.isWater && !order.phase.finished && order.orderId.isNotBlank())
        TextButton(onClick = requestCancel, enabled = !busy) { Text("取消未付款订单") }
}

private fun orderHeadline(order: LocalOrder): String = when (order.phase) {
    OrderPhase.PAID -> if (order.isWater) "饮水账单已支付" else "付款已确认"
    OrderPhase.CREATING, OrderPhase.UNCONFIRMED -> if (order.isWater) "饮水账单结果待确认" else "下单结果待确认"
    OrderPhase.CREATED -> if (order.isWater) "饮水账单待付款" else "订单待付款"
    OrderPhase.PAYMENT_PENDING -> if (order.isWater) "饮水账单付款待确认" else "付款结果待确认"
    OrderPhase.CANCELLED -> if (order.isWater) "饮水账单已取消" else "订单已取消"
    OrderPhase.REJECTED -> if (order.isWater) "饮水账单未生成" else "下单未成功"
    OrderPhase.CLOSED -> if (order.isWater) "饮水账单已关闭" else "订单已关闭"
    OrderPhase.COMPLETED -> if (order.isWater) "饮水账单已完成" else "订单已完成"
    OrderPhase.REFUNDING -> if (order.isWater) "饮水账单退款处理中" else "退款处理中"
    OrderPhase.REFUNDED -> if (order.isWater) "饮水账单已退款" else "订单已退款"
}

private fun orderDescription(order: LocalOrder): String {
    if (order.isWater) return when (order.phase) {
        OrderPhase.PAID -> "饮水结算账单已支付，金额与状态以平台记录为准。"
        OrderPhase.CREATING, OrderPhase.UNCONFIRMED -> "饮水账单结果待确认，请先查询订单状态。"
        OrderPhase.CREATED -> "饮水结算账单已创建，可以继续付款；若已在原应用付款，请刷新状态。"
        OrderPhase.PAYMENT_PENDING -> "饮水账单付款结果尚未确认，请先刷新订单状态。"
        OrderPhase.CANCELLED -> "饮水账单已取消，请以平台记录为准。"
        OrderPhase.REJECTED -> "饮水账单未生成成功，请返回饮水页核对设备状态。"
        OrderPhase.CLOSED -> "饮水账单已超时或失效，请以平台记录为准。"
        OrderPhase.COMPLETED -> "本次饮水已结束，账单已完成。"
        OrderPhase.REFUNDING -> "平台正在处理饮水账单退款，请在原应用查看进度。"
        OrderPhase.REFUNDED -> "平台已标记饮水账单退款完成，请核对到账情况。"
    }
    return when (order.phase) {
        OrderPhase.PAID -> "请查看洗衣机运行状态。"
        OrderPhase.CREATING, OrderPhase.UNCONFIRMED ->
            if (order.orderNo.isBlank()) "请先在原应用核对这笔订单，避免重复下单。" else "付款结果尚未确认，请先查询订单状态。"
        OrderPhase.CREATED -> "订单已创建，可以继续付款；若已在原应用付款，请刷新状态。"
        OrderPhase.PAYMENT_PENDING -> "付款结果尚未确认，请先查询订单状态。"
        OrderPhase.CANCELLED, OrderPhase.REJECTED -> "可以返回洗衣，重新选择模式。"
        OrderPhase.CLOSED -> "订单已超时或失效，可以返回洗衣重新选择模式。"
        OrderPhase.COMPLETED -> "本次洗衣已结束。"
        OrderPhase.REFUNDING -> "平台正在处理退款，请在原应用查看进度。"
        OrderPhase.REFUNDED -> "平台已标记退款完成，请核对到账情况。"
    }
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
