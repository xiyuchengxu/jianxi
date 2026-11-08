package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import dev.rain.laundry.i18n.LocalizedText as Text
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
import dev.rain.laundry.i18n.AppStrings

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
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.orderOpenRequest) {
        if (state.orderOpenRequest > 0) { aboutOpen = false; themeOpen = false; settingsOpen = false; notificationsOpen = false }
    }
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
        notificationsOpen -> AppScreen.NOTIFICATIONS
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
        themeOpen || settingsOpen || notificationsOpen -> NavigationTab.SETTINGS
        visiblePage == Page.WATER -> NavigationTab.WATER
        visiblePage == Page.ORDERS -> NavigationTab.ORDERS
        else -> NavigationTab.LAUNDRY
    }
    val selectedTab = tabs.indexOf(currentTab).coerceAtLeast(0)
    val selectTab: (Int) -> Unit = { index ->
        if (!state.busy) when (val tab = tabs.getOrNull(index)) {
            NavigationTab.SETTINGS -> { settingsOpen = true; themeOpen = false; notificationsOpen = false }
            NavigationTab.LAUNDRY, NavigationTab.WATER, NavigationTab.ORDERS -> {
                val destination = when (tab) { NavigationTab.LAUNDRY -> Page.HOME; NavigationTab.WATER -> Page.WATER; else -> Page.ORDERS }
                if (settingsOpen || themeOpen || notificationsOpen || state.page != destination) {
                    settingsOpen = false
                    themeOpen = false
                    notificationsOpen = false
                    vm.navigate(destination)
                }
            }
            null -> Unit
        }
    }
    val openWater: () -> Unit = {
        if (settings.waterEnabled) {
            settingsOpen = false; themeOpen = false; notificationsOpen = false
            // Reopen the stored transaction explicitly; plain navigation after this
            // change no longer restores an idle outlet/device from the vault.
            if (state.waterSession != null) vm.openWaterSession() else vm.navigate(Page.WATER)
        } else {
            settingsOpen = true; themeOpen = false; notificationsOpen = false
            vm.notice(appText(R.string.msg_5ee487891e56))
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
    LaunchedEffect(lifecycleOwner, state.loggedIn) {
        if (state.loggedIn) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { vm.refreshLaundryProgress(); delay(15_000) }
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
            snackbar.showSnackbar(localizedMessage(message))
            if (vm.state.value.message == message) vm.clearMessage()
        }
    }
    BackHandler(state.loggedIn && !settingsOpen && !themeOpen && !notificationsOpen && state.page !in listOf(Page.HOME, Page.WATER, Page.ORDERS) && !state.busy) {
        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
    }
    BackHandler(!state.loggedIn && settingsOpen && !themeOpen && !notificationsOpen) { settingsOpen = false }
    BackHandler(themeOpen) { themeOpen = false; settingsOpen = true }
    BackHandler(notificationsOpen) { notificationsOpen = false; settingsOpen = true }
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
                        Icon(when { notificationsOpen -> Icons.Outlined.NotificationsActive; themeOpen -> Icons.Outlined.Palette; settingsOpen -> Icons.Outlined.Settings; visiblePage == Page.WATER -> Icons.Outlined.WaterDrop;
                            else -> Icons.Outlined.LocalLaundryService }, null, tint = colors.primary)
                        Text(when { notificationsOpen -> appText(R.string.msg_4b6ded8a7f18); themeOpen -> appText(R.string.msg_788db1cfec2a); settingsOpen -> appText(R.string.msg_df3d58c7d84b); visiblePage == Page.WATER -> appText(R.string.msg_6edae041a323); else -> appText(R.string.msg_2b207da009d1) }, fontWeight = FontWeight.Bold)
                    }
                }, navigationIcon = {
                    if (themeOpen || notificationsOpen) IconButton(onClick = { themeOpen = false; notificationsOpen = false; settingsOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, appText(R.string.scanner_close))
                    } else if (settingsOpen && !state.loggedIn) IconButton(onClick = { settingsOpen = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, appText(R.string.scanner_close))
                    } else if (!settingsOpen && state.loggedIn && state.page in listOf(Page.DEVICE, Page.CHECKOUT)) IconButton(onClick = {
                        vm.navigate(if (state.page == Page.CHECKOUT) Page.DEVICE else Page.HOME)
                    }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, appText(R.string.scanner_close)) }
                }, actions = {
                    if (!state.loggedIn && !themeOpen && !settingsOpen) IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = appText(R.string.msg_df3d58c7d84b))
                    }
                    IconButton(onClick = { aboutOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Info, contentDescription = appText(R.string.msg_c1cb5f40de7c))
                    }
                    if (state.loggedIn) TextButton(onClick = { logoutOpen = true }, enabled = !state.busy) { Text(appText(R.string.msg_498e1d59b4d7)) }
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
                                        AppScreen.HOME -> HomeScreen(state, settings.homeMessage, onScan, { inputOpen = true }, { vm.navigate(Page.ORDERS) })
                                        AppScreen.SETTINGS -> SettingsScreen(settings, onThemeChange, { themeOpen = true }, !state.busy,
                                            { notificationsOpen = true })
                                        AppScreen.NOTIFICATIONS -> NotificationSettingsScreen(state.laundryProgressError)
                                        AppScreen.WATER -> WaterScreen(state, onScan, { inputOpen = true }, vm::selectWaterOutlet,
                                            onWaterStart, vm::endWater, { vm.refreshWater() }, { vm.navigate(Page.ORDERS) }, vm::selectWaterPayment)
                                        AppScreen.DEVICE -> DeviceScreen(state, vm::selectMode, vm::preview)
                                        AppScreen.CHECKOUT -> CheckoutScreen(state, vm::selectPayment, onPay)
                                        AppScreen.ORDERS -> OrdersScreen(state, { vm.refreshOrder(it) }, { vm.resumePayment(it) }, { vm.cancelOrder(it) }, openWater)
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
            title = { Text(appText(R.string.msg_8e878de7a527)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(appText(R.string.msg_1c8a94e1b712))
                    OutlinedTextField(raw, { raw = it.take(4096) }, label = { Text(appText(R.string.msg_2d0222b61b9a)) }, minLines = 3, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = {
                inputOpen = false; vm.scan(raw, settings.laundryEnabled, settings.waterEnabled)
            }, enabled = raw.isNotBlank()) { Text(appText(R.string.msg_93f6c90cc986)) } },
            dismissButton = { TextButton(onClick = { inputOpen = false }) { Text(appText(R.string.msg_2cd0f3be8738)) } })
    }
    if (logoutOpen) AlertDialog(onDismissRequest = { logoutOpen = false }, title = { Text(appText(R.string.msg_b6af1a5049fc)) },
        text = { Text(appText(R.string.msg_cdc013bc1922)) },
        confirmButton = { TextButton(onClick = { logoutOpen = false; vm.logout() }) { Text(appText(R.string.msg_498e1d59b4d7)) } },
        dismissButton = { TextButton(onClick = { logoutOpen = false }) { Text(appText(R.string.msg_2cd0f3be8738)) } })
}

private enum class AppScreen { LOGIN, HOME, DEVICE, CHECKOUT, WATER, ORDERS, SETTINGS, THEME, NOTIFICATIONS }

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
    Text(appText(R.string.msg_5bcd70afb28d), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    Text(appText(R.string.msg_b25ef479aebd), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(appText(R.string.msg_b4f947a472c7), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, label = { Text(appText(R.string.msg_6f52cc94db65)) },
                prefix = { Text("+86  ") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text(appText(R.string.msg_18e6bb3f9968)) },
                singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                trailingIcon = { TextButton(onClick = { send(phone) }, enabled = accepted && !state.busy && phone.length == 11 && state.smsCooldown == 0) {
                    Text(if (state.smsCooldown > 0) "${state.smsCooldown}s" else appText(R.string.msg_3b91d186d44e))
                } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(accepted, { accepted = it }, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText(R.string.msg_22c8f9f73dc0), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { login(phone, code) }, enabled = accepted && !state.busy && phone.length == 11 && code.length >= 4,
                modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(appText(R.string.msg_1e2df9c3075a), style = MaterialTheme.typography.titleMedium) }
        }
    }
    Text(appText(R.string.msg_54744c98c1d5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HomeScreen(state: UiState, homeMessage: String, scan: () -> Unit, paste: () -> Unit, orders: () -> Unit) {
    Text(displayHomeMessage(homeMessage), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, localize = false)
    Text(state.phoneLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, localize = false)
    Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.LocalLaundryService, null, modifier = Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
            Text(appText(R.string.msg_a24b2df2ec2f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(appText(R.string.msg_2050c192dabd), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(appText(R.string.msg_6415984a1009), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    OutlinedButton(onClick = paste, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(appText(R.string.msg_8e878de7a527))
    }
    state.order?.takeIf { !it.phase.finished }?.let {
        OutlinedCard(onClick = orders, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text(appText(R.string.msg_d4b832a716f8)); Text(appText(R.string.msg_24c899792f14), style = MaterialTheme.typography.bodySmall) }
                Icon(Icons.Outlined.ChevronRight, appText(R.string.msg_7546a59d0f68))
            }
        }
    }
}

@Composable
private fun DeviceScreen(state: UiState, select: (WashMode) -> Unit, next: () -> Unit) {
    val device = state.device ?: return
    Text(appText(R.string.msg_62be6c883399), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    InfoCard(device.name, if (device.remainingMinutes > 0) appText(R.string.msg_7daf2930ebfb , device.remainingMinutes) else appText(R.string.msg_ce33b71f9fdd))
    if (device.error.isNotBlank()) Text(device.error, color = MaterialTheme.colorScheme.error)
    device.modes.forEach { mode ->
        OutlinedCard(onClick = { select(mode) }, enabled = !state.busy, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(if (state.mode == mode) 2.dp else 1.dp, if (state.mode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.outlinedCardColors(containerColor = if (state.mode == mode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.mode == mode, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(mode.name, style = MaterialTheme.typography.titleMedium, localize = false); if (mode.feature.isNotBlank()) Text(mode.feature.take(80), style = MaterialTheme.typography.bodySmall, localize = false) }
                Text("¥${mode.price}", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    Button(onClick = next, enabled = state.mode != null && !state.busy && device.remainingMinutes <= 0 && device.error.isBlank(),
        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(appText(R.string.msg_abbd0e07316e)) }
}

@Composable
private fun CheckoutScreen(state: UiState, choose: (PayRoute) -> Unit, pay: () -> Unit) {
    val quote = state.quote ?: return
    Text(appText(R.string.msg_afb44149eeaf), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    InfoCard(quote.device.name, quote.mode.name)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Line(appText(R.string.msg_b7b30111f5a8), quote.mode.name)
            Line(appText(R.string.msg_5e29d121dda0), appText(R.string.msg_35cf44ac0fcb))
            HorizontalDivider()
            Text(appText(R.string.msg_7895f1bfe9af), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${quote.amount}", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(appText(R.string.msg_ca7223717fa6), style = MaterialTheme.typography.titleMedium)
    state.methods.forEach { method ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !state.busy) { choose(method) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(state.payMethod == method, onClick = { choose(method) }, enabled = !state.busy)
            Text(method.title, modifier = Modifier.weight(1f))
            Text(appText(R.string.msg_6b1933e75c74), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = pay, enabled = !state.busy && state.payMethod != null, modifier = Modifier.fillMaxWidth().height(58.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(appText(R.string.msg_16de867a06ae , quote.amount), style = MaterialTheme.typography.titleMedium)
    }
    Text(appText(R.string.msg_749b9fdc6f64), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class OrderGroup(val label: String) { ALL("全部"), LAUNDRY("洗衣"), WATER("饮水") }

@Composable
private fun OrdersScreen(
    state: UiState, refresh: (LocalOrder) -> Unit, resume: (LocalOrder) -> Unit,
    cancel: (LocalOrder) -> Unit, water: () -> Unit
) {
    var group by rememberSaveable { mutableStateOf(OrderGroup.ALL) }
    var cancelTarget by remember { mutableStateOf<LocalOrder?>(null) }
    val owner = state.orderHistory.firstOrNull()?.owner ?: state.order?.owner ?: state.waterSession?.owner.orEmpty()
    // The pointer / legacy settlement are migration fallbacks. Saved history has the latest bill state.
    val history = OrderHistory.merge(owner,
        listOfNotNull(state.waterSession?.settlementOrder, state.order) + state.orderHistory)
    var expandedKey by rememberSaveable(owner) {
        mutableStateOf(state.order?.takeIf { !it.phase.finished }?.let(OrderHistory::key))
    }
    LaunchedEffect(state.orderOpenRequest) {
        state.orderFocusKey?.let { group = OrderGroup.ALL; expandedKey = it }
    }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(history.any { !it.isWater && it.phase == OrderPhase.PAID }) {
        while (isActive) { clock = System.currentTimeMillis(); delay(1000) }
    }
    val session = state.waterSession
    val pendingOutsideHistory = state.order?.takeIf { current ->
        !current.phase.finished && history.none { OrderHistory.sameOrder(it, current) }
    }

    Text(appText(R.string.msg_7c7dea47f575), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderGroup.entries.forEach { candidate ->
            FilterChip(selected = group == candidate, onClick = { group = candidate },
                label = { Text(candidate.label) }, enabled = !state.busy)
        }
    }

    // An old unresolved intent remains actionable even if it predates the bounded history.
    pendingOutsideHistory?.takeIf {
        group == OrderGroup.ALL || (it.isWater && group == OrderGroup.WATER) || (!it.isWater && group == OrderGroup.LAUNDRY)
    }?.let { pending ->
        Text(appText(R.string.msg_5d5c5a7050f5), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OrderHistoryCard(pending, state.busy, expandedKey == OrderHistory.key(pending),
            { expandedKey = if (expandedKey == OrderHistory.key(pending)) null else OrderHistory.key(pending) },
            { refresh(pending) }, { resume(pending) }, { cancelTarget = pending }, clock)
    }

    if (history.isEmpty() && session == null && pendingOutsideHistory == null) {
        Spacer(Modifier.height(20.dp)); HeroIcon(Icons.AutoMirrored.Outlined.ReceiptLong)
        Text(when (group) {
            OrderGroup.WATER -> appText(R.string.msg_a0aa4b780049)
            OrderGroup.LAUNDRY -> appText(R.string.msg_5d3f476b86fd)
            OrderGroup.ALL -> appText(R.string.msg_874e82006913)
        }, style = MaterialTheme.typography.titleLarge)
        Text(appText(R.string.msg_bc8383e2aaa8), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    @Composable
    fun records(orders: List<LocalOrder>) {
        OrderHistory.weeklyGroups(orders).forEach { week ->
            val prefix = when {
                week.title.startsWith("本周 · ") -> appText(R.string.msg_a0686c2646d6)
                week.title.startsWith("上周 · ") -> appText(R.string.msg_80a1a04791c5)
                else -> ""
            }
            Text(appText(R.string.msg_d727d75a30fb, prefix, week.start, week.end), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            week.orders.forEach { order ->
                val identity = OrderHistory.key(order)
                key(identity) {
                    OrderHistoryCard(order, state.busy, expandedKey == identity,
                        { expandedKey = if (expandedKey == identity) null else identity },
                        { refresh(order) }, { resume(order) }, { cancelTarget = order }, clock,
                        if (state.orderFocusKey == identity) state.orderOpenRequest else 0)
                }
            }
        }
    }
    if (group != OrderGroup.WATER) {
        val laundry = history.filterNot { it.isWater }
        OrderGroupHeader(Icons.Outlined.LocalLaundryService, appText(R.string.msg_bc982c81c510), appText(R.string.msg_478a69f47b5e , laundry.size))
        if (laundry.isEmpty()) Text(appText(R.string.msg_ac301767677f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else records(laundry)
    }
    if (group != OrderGroup.LAUNDRY) {
        val bills = history.filter { it.isWater }
        OrderGroupHeader(Icons.Outlined.WaterDrop, appText(R.string.msg_6edae041a323), appText(R.string.msg_1f405289ff28 , bills.size))
        // A running session is separate from the 20 order records and is the only restorable entry.
        if (session != null && (session.active || bills.isEmpty())) WaterSessionEntry(session, water)
        if (bills.isEmpty() && session == null) Text(appText(R.string.msg_822a8903fcb0),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else records(bills)
    }
    cancelTarget?.let { target ->
        AlertDialog(onDismissRequest = { cancelTarget = null }, title = { Text(appText(R.string.msg_51ebba34a15f)) },
            text = { Text(appText(R.string.msg_94654c53105c , target.deviceName, target.modeName)) },
            confirmButton = { TextButton(onClick = { cancelTarget = null; cancel(target) }, enabled = !state.busy) { Text(appText(R.string.msg_b741efb628be)) } },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text(appText(R.string.msg_f65b4f1a14a2)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrderHistoryCard(
    order: LocalOrder, busy: Boolean, expanded: Boolean, toggle: () -> Unit,
    refresh: () -> Unit, resume: () -> Unit, cancel: () -> Unit,
    now: Long, focusRequest: Long = 0
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) { delay(250); bringIntoView.bringIntoView() }
    }
    OutlinedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (order.isWater) Icons.Outlined.WaterDrop else Icons.Outlined.LocalLaundryService,
                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(order.deviceName, style = MaterialTheme.typography.titleSmall, localize = false)
                Text(order.modeName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, localize = false)
                Text(orderHeadline(order), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(SimpleDateFormat("MM-dd HH:mm", AppStrings.locale()).format(Date(order.createdAt)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("¥${order.amount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) appText(R.string.msg_00d78bd5bca3) else appText(R.string.msg_b140a6401279), modifier = Modifier.size(20.dp))
        }
        LaundryProgressIndicator(order, now)
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrderDetail(order, busy, refresh, resume, cancel)
            }
        }
    }
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
                Text(if (session.active) appText(R.string.msg_2ded5acf5f36) else appText(R.string.msg_98b03d8bcb49))
                Text(session.device.name + " · " + session.outlet.name, style = MaterialTheme.typography.bodySmall, localize = false)
                Text(SimpleDateFormat("MM-dd HH:mm", AppStrings.locale()).format(Date(session.startedAt)) +
                    (if (session.amount.isNotBlank()) " · ¥${session.amount}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.active) Icon(Icons.Outlined.ChevronRight, appText(R.string.msg_dee754f9cb2d))
        }
    }
}

@Composable
private fun OrderDetail(order: LocalOrder, busy: Boolean, refresh: () -> Unit, resume: () -> Unit, requestCancel: () -> Unit) {
    val cancelled = order.phase == OrderPhase.CANCELLED
    Text(orderDescription(order), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Line(appText(R.string.msg_e1506406a5bd), order.deviceName)
            Line(if (order.isWater) appText(R.string.msg_717d0bf23fb3) else appText(R.string.msg_47a270081ab2), order.modeName)
            Line(appText(R.string.msg_ffc624300de8), "¥${order.amount}")
            Line(appText(R.string.msg_ca7223717fa6), localizedMessage(order.payMethod.title))
            Line(if (order.isWater) appText(R.string.msg_9eaf7337eac7) else appText(R.string.msg_1e75a9bc677c), SimpleDateFormat("MM-dd HH:mm", AppStrings.locale()).format(Date(order.createdAt)))
            if (order.orderNo.isNotBlank()) Text(appText(R.string.msg_a01fa2926a83 , order.orderNo), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (order.orderNo.isNotBlank() && !cancelled) OutlinedButton(onClick = refresh, enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(appText(R.string.msg_f727d5abd647)) }
    if (!order.phase.finished && order.orderNo.isNotBlank() && (order.payMethod == PayMethod.ALIPAY || order.phase == OrderPhase.CREATED))
        Button(onClick = resume, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(appText(R.string.msg_6253728e4fa9)) }
    if (!order.isWater && !order.phase.finished && order.orderId.isNotBlank())
        TextButton(onClick = requestCancel, enabled = !busy) { Text(appText(R.string.msg_adcead944a6f)) }
}

private fun orderHeadline(order: LocalOrder): String = when (order.phase) {
    OrderPhase.PAID -> if (order.isWater) appText(R.string.msg_208e9e1652bf) else appText(R.string.msg_65687255173c)
    OrderPhase.CREATING, OrderPhase.UNCONFIRMED -> if (order.isWater) appText(R.string.msg_4c8eb5753a4e) else appText(R.string.msg_065e184d07a3)
    OrderPhase.CREATED -> if (order.isWater) appText(R.string.msg_c2387dfab4aa) else appText(R.string.msg_ff7e349a58d1)
    OrderPhase.PAYMENT_PENDING -> if (order.isWater) appText(R.string.msg_d438416ae194) else appText(R.string.msg_c47810fe6c5c)
    OrderPhase.CANCELLED -> if (order.isWater) appText(R.string.msg_89543c817609) else appText(R.string.msg_bc355408517f)
    OrderPhase.REJECTED -> if (order.isWater) appText(R.string.msg_3eb2e1441f78) else appText(R.string.msg_109b1efb4f46)
    OrderPhase.CLOSED -> if (order.isWater) appText(R.string.msg_86a84936749d) else appText(R.string.msg_ff4fc4a36c34)
    OrderPhase.COMPLETED -> if (order.isWater) appText(R.string.msg_d26366e4e8b6) else appText(R.string.msg_51f0310e9d93)
    OrderPhase.REFUNDING -> if (order.isWater) appText(R.string.msg_3cb723dd618e) else appText(R.string.msg_5642ebf21120)
    OrderPhase.REFUNDED -> if (order.isWater) appText(R.string.msg_2c5f66a724fd) else appText(R.string.msg_b01e2d77a8c6)
}

private fun orderDescription(order: LocalOrder): String {
    if (order.isWater) return when (order.phase) {
        OrderPhase.PAID -> appText(R.string.msg_832c13565546)
        OrderPhase.CREATING, OrderPhase.UNCONFIRMED -> appText(R.string.msg_e97395fabcb3)
        OrderPhase.CREATED -> appText(R.string.msg_4c5c6272eeb1)
        OrderPhase.PAYMENT_PENDING -> appText(R.string.msg_13dabacb2def)
        OrderPhase.CANCELLED -> appText(R.string.msg_6be5a3ca9454)
        OrderPhase.REJECTED -> appText(R.string.msg_0ee6972c135c)
        OrderPhase.CLOSED -> appText(R.string.msg_cc0bf0ebd2ce)
        OrderPhase.COMPLETED -> appText(R.string.msg_0f4d2cd6e467)
        OrderPhase.REFUNDING -> appText(R.string.msg_21f2cb8d89a7)
        OrderPhase.REFUNDED -> appText(R.string.msg_3098a02a0c34)
    }
    return when (order.phase) {
        OrderPhase.PAID -> appText(R.string.msg_87da2d041151)
        OrderPhase.CREATING, OrderPhase.UNCONFIRMED ->
            if (order.orderNo.isBlank()) appText(R.string.msg_8bea5e0f6086) else appText(R.string.msg_0baa295b60a1)
        OrderPhase.CREATED -> appText(R.string.msg_b621b6e40986)
        OrderPhase.PAYMENT_PENDING -> appText(R.string.msg_0baa295b60a1)
        OrderPhase.CANCELLED, OrderPhase.REJECTED -> appText(R.string.msg_f0fd094c9fed)
        OrderPhase.CLOSED -> appText(R.string.msg_6132aaeef659)
        OrderPhase.COMPLETED -> appText(R.string.msg_cae4a3724dad)
        OrderPhase.REFUNDING -> appText(R.string.msg_2c47626fe8e8)
        OrderPhase.REFUNDED -> appText(R.string.msg_829614d0d5f9)
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
        Column { Text(title, style = MaterialTheme.typography.titleMedium, localize = false); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, localize = false) }
    }
}
@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), fontWeight = FontWeight.Medium, localize = false)
    }
}
