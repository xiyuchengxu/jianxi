package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit, modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 28.dp) {
    val colors = MaterialTheme.colorScheme
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicActive = dynamicSupported && settings.dynamicColor && settings.designStyle == DesignStyle.MATERIAL
    val miuixActive = settings.designStyle == DesignStyle.MIUIX
    val wallpaper = LocalWallpaperColorInfo.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = contentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "preview") { ThemePreview(settings) }

        item(key = "design_style") {
            SettingGroup("theme.style", appText(R.string.theme_design_style_title), Icons.Outlined.DashboardCustomize) {
                DesignStyle.entries.forEachIndexed { index, style ->
                    if (index > 0) SettingDivider()
                    ChoiceRow("theme.style.${style.name.lowercase()}", appText(if (style == DesignStyle.MIUIX) R.string.theme_design_miuix else R.string.theme_design_material),
                        if (style == DesignStyle.MIUIX) Icons.Outlined.Tune else Icons.Outlined.Palette,
                        settings.designStyle == style,
                        { onChange(settings.copy(designStyle = style)) })
                }
            }
        }

        // Existing installations can still return to their system font.
        if (settings.font != AppFont.SYSTEM) item(key = "restore_system_font") {
            SettingGroup("theme.font", appText(R.string.font_settings_title), Icons.Outlined.TextFields,
                fontFamily = FontFamily.Default) {
                ChoiceRow("theme.font.system", appText(R.string.font_system_title), Icons.Outlined.PhoneAndroid, false,
                    { onChange(settings.copy(font = AppFont.SYSTEM)) }, fontFamily = FontFamily.Default)
            }
        }

        item(key = "home_message") {
            SettingGroup("theme.home.message", appText(R.string.msg_3e0d676d2d85), Icons.Outlined.EditNote) {
                HomeMessageSettings(settings, onChange)
            }
        }

        item(key = "splash") {
            SettingGroup("theme.splash", appText(R.string.msg_e94328786abb), Icons.Outlined.PhotoLibrary) {
                SplashSettings(settings, onChange)
            }
        }

        item(key = "mode") {
            SettingGroup("theme.mode", appText(R.string.msg_44fb814b166e), Icons.Outlined.Brightness6) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingDivider()
                    ChoiceRow("theme.mode.${mode.name.lowercase()}", mode.title, when (mode) {
                        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Outlined.LightMode
                        ThemeMode.DARK -> Icons.Outlined.DarkMode
                    }, settings.mode == mode, { onChange(settings.copy(mode = mode)) })
                }
            }
        }

        item(key = "black") {
            SettingGroup("theme.black", appText(R.string.msg_46bad67d0636), Icons.Outlined.Contrast) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Nightlight, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText(R.string.msg_56fe9a6dd1aa), style = MaterialTheme.typography.bodyLarge)
                        Text(appText(R.string.msg_247ab5060860), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    AppSwitch(settings.pureBlack, { onChange(settings.copy(pureBlack = it)) },
                        modifier = Modifier.semantics { contentDescription = appText(R.string.msg_56fe9a6dd1aa) })
                }
            }
        }

        item(key = "palette") {
            SettingGroup("theme.palette", appText(R.string.msg_d007cc91971f), Icons.Outlined.Palette) {
                FlowRow(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemePalette.entries.forEach { palette ->
                        val chosen = !dynamicActive && !miuixActive && settings.palette == palette
                        Box(Modifier.size(48.dp).alpha(if (dynamicActive || miuixActive) .4f else 1f).clip(CircleShape)
                            .selectable(chosen, enabled = !dynamicActive && !miuixActive, role = Role.RadioButton, onClick = { onChange(settings.copy(palette = palette)) })
                            .semantics { contentDescription = appText(R.string.msg_4c0130832ac1 , localizedMessage(palette.title)) }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(40.dp).then(if (chosen) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier)
                                .padding(4.dp).background(palette.accent(), CircleShape), contentAlignment = Alignment.Center) {
                                if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(19.dp), tint = Color.White)
                            }
                        }
                    }
                }
                Text(when {
                    miuixActive -> appText(R.string.theme_design_miuix_fixed_colors)
                    !dynamicActive -> appText(R.string.msg_755f809f9c52 , localizedMessage(settings.palette.title))
                    wallpaper.seed != null -> appText(R.string.msg_03dd0c88d8ac)
                    wallpaper.loading -> appText(R.string.msg_5ecc022c1ca8)
                    else -> appText(R.string.msg_f3ef5f2ab85b)
                },
                    Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.primary)
                SettingDivider()
                var styleMenu by remember { mutableStateOf(false) }
                val menuScale = animateFloatAsState(if (styleMenu) .982f else 1f,
                    spring(dampingRatio = .5f, stiffness = 600f), label = "styleAnchor")
                Box {
                    AdjustableSettingRow("theme.palette.style", Modifier.fillMaxWidth().alpha(if (dynamicActive || miuixActive) .4f else 1f)
                        .graphicsLayer { scaleX = menuScale.value; scaleY = menuScale.value }
                        .clickable(enabled = !dynamicActive && !miuixActive) { styleMenu = true }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(appText(R.string.msg_4d29b30867a0), Modifier.weight(1f))
                        Text(if (dynamicActive || miuixActive) appText(R.string.msg_7eb336e42cb5) else settings.colorStyle.title, color = colors.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.UnfoldMore, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    ElasticDropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                        ColorStyle.entries.forEach { style ->
                            DropdownMenuItem(text = { Text(style.title) }, onClick = {
                                styleMenu = false
                                onChange(settings.copy(colorStyle = style))
                            }, trailingIcon = {
                                if (settings.colorStyle == style) Icon(Icons.Outlined.Check, null, tint = colors.primary)
                            })
                        }
                    }
                }
            }
        }

        item(key = "wallpaper") {
            SettingGroup("theme.wallpaper", appText(R.string.wallpaper_settings_title), Icons.Outlined.Wallpaper) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText(R.string.msg_458e4ccaafb1), style = MaterialTheme.typography.bodyLarge)
                        Text(when {
                            miuixActive -> appText(R.string.theme_design_miuix_fixed_colors)
                            !dynamicSupported -> appText(R.string.msg_e7e3ad41fdb9)
                            dynamicActive -> appText(R.string.msg_0b8351751e94)
                            else -> appText(R.string.msg_d03a42384c95)
                        }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    AppSwitch(checked = dynamicActive, enabled = dynamicSupported && !miuixActive,
                        onCheckedChange = { onChange(settings.copy(dynamicColor = it)) },
                        modifier = Modifier.semantics { contentDescription = appText(R.string.msg_458e4ccaafb1) })
                }
                if (dynamicActive) {
                    SettingDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        wallpaper.seed?.let { seed ->
                            Box(Modifier.size(20.dp).background(Color(seed), CircleShape)
                                .border(1.dp, colors.outlineVariant, CircleShape))
                        }
                        Text(when {
                            wallpaper.loading -> appText(R.string.msg_ac08701c24ca)
                            wallpaper.seed != null -> appText(R.string.msg_1cecf188f254)
                            else -> appText(R.string.msg_13c1f4bc5d91)
                        }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        AppTextButton(onClick = wallpaper.refresh, enabled = !wallpaper.loading) {
                            Text(appText(R.string.msg_d22657dec5d1))
                        }
                    }
                }
                SettingDivider()
                WallpaperBackgroundSettings(settings, onChange)
            }
        }

        item(key = "navigation") {
            SettingGroup("theme.navigation", appText(R.string.msg_894df8bd55a2), Icons.Outlined.Layers) {
                ChoiceRow("theme.navigation.floating", appText(R.string.msg_b70c4e8a2e1d), Icons.Outlined.ViewAgenda, settings.bottomBar == BottomBarStyle.FLOATING,
                    { onChange(settings.copy(bottomBar = BottomBarStyle.FLOATING)) }, appText(R.string.msg_c4af153a611c , localizedMessage(settings.glassLevel.title)))
                SettingDivider()
                ChoiceRow("theme.navigation.fixed", appText(R.string.msg_d4b3f49cd2e4), Icons.Outlined.VerticalAlignBottom, settings.bottomBar == BottomBarStyle.FIXED,
                    { onChange(settings.copy(bottomBar = BottomBarStyle.FIXED)) }, appText(R.string.msg_b3b023c8f791))
            }
        }

        item(key = "glass") {
            SettingGroup("theme.glass", appText(R.string.msg_b1c9c1d036e2), Icons.Outlined.WaterDrop) {
                GlassLevel.entries.forEachIndexed { index, level ->
                    if (index > 0) SettingDivider()
                    ChoiceRow("theme.glass.${level.name.lowercase()}", level.title, when (level) {
                        GlassLevel.NONE -> Icons.Outlined.LayersClear
                        GlassLevel.LIGHT -> Icons.Outlined.BlurOn
                        GlassLevel.FULL -> Icons.Outlined.WaterDrop
                    }, settings.glassLevel == level, { onChange(settings.copy(glassLevel = level)) })
                }
                SettingDivider()
                if (settings.bottomBar == BottomBarStyle.FIXED) Text(appText(R.string.msg_65e1d3722879),
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                GlassStylePreview(settings.glassLevel, settings.visibleTabs)
            }
        }

        item(key = "swipe") {
            SettingGroup("theme.swipe", appText(R.string.msg_3f2191b43607), Icons.Outlined.Swipe) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Swipe, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText(R.string.msg_088dce96a836), style = MaterialTheme.typography.bodyLarge)
                    }
                    AppSwitch(settings.swipeNavigation, { onChange(settings.copy(swipeNavigation = it)) },
                        modifier = Modifier.semantics { contentDescription = appText(R.string.msg_088dce96a836) })
                }
            }
        }

        item(key = "animation") {
            SettingGroup("theme.animation", appText(R.string.msg_30b6558e1ec2), Icons.Outlined.Animation) {
                PageAnimation.entries.forEachIndexed { index, animation ->
                    if (index > 0) SettingDivider()
                    ChoiceRow("theme.animation.${animation.name.lowercase()}", animation.title, when (animation) {
                        PageAnimation.SLIDE -> Icons.Outlined.Swipe
                        PageAnimation.FADE -> Icons.Outlined.BlurOn
                        PageAnimation.NONE -> Icons.Outlined.MotionPhotosOff
                    }, settings.animation == animation, { onChange(settings.copy(animation = animation)) })
                }
            }
        }
        item(key = "savedHint") {
            Text(appText(R.string.msg_7f48fe35e1c4), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingGroup(controlId: String, title: String, icon: ImageVector, fontFamily: FontFamily? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = fontFamily)
        }
        AppCard(controlId = controlId, shape = appShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun SettingDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun ChoiceRow(controlId: String, title: String, icon: ImageVector, selected: Boolean, onSelect: () -> Unit,
    subtitle: String? = null, fontFamily: FontFamily? = null) {
    AdjustableControl(controlId, Modifier) { target ->
        val rowModifier = if (LocalControlHasOverride.current)
            target.background(MaterialTheme.colorScheme.surfaceContainerHigh.controlContainer()) else target
        val colors = MaterialTheme.colorScheme
        if (LocalMiuixStyle.current) {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = title, summary = subtitle, onClick = onSelect,
                modifier = rowModifier.semantics { role = Role.RadioButton; this.selected = selected },
                leftAction = {
                    Icon(icon, null, Modifier.padding(end = 14.dp).size(22.dp), tint = colors.onSurfaceVariant)
                },
                rightActions = {
                    if (selected) Icon(Icons.Outlined.Check, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
            )
            return@AdjustableControl
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale = animateFloatAsState(if (pressed) .974f else 1f,
            spring(dampingRatio = .48f, stiffness = 650f), label = "choicePress")
        val iconColor by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
            spring(stiffness = 500f), label = "choiceColor")
        Row(rowModifier.fillMaxWidth().heightIn(min = 64.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .selectable(selected, interactionSource = interaction, indication = LocalIndication.current,
                onClick = onSelect, role = Role.RadioButton).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(34.dp).background(iconColor.controlContainer(),
                RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontFamily = fontFamily)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, fontFamily = fontFamily)
            }
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(selected,
                    enter = scaleIn(spring(dampingRatio = .46f, stiffness = 520f), initialScale = .4f) + fadeIn(tween(90)),
                    exit = scaleOut(tween(80), targetScale = .6f) + fadeOut(tween(80))) {
                    Icon(Icons.Outlined.Check, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** A live miniature of this app; all colors and bar layout follow the real settings. */
@Composable
private fun ThemePreview(settings: ThemeSettings) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().height(264.dp).clearAndSetSemantics { contentDescription = appText(R.string.msg_f78a40791721) },
        shape = appShape(30.dp), color = colors.surfaceContainer.controlContainer()) {
        Box(contentAlignment = Alignment.Center) {
            Surface(Modifier.width(132.dp).height(234.dp), shape = RoundedCornerShape(24.dp),
                color = colors.background, border = BorderStroke(5.dp, colors.outlineVariant)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(36.dp).height(5.dp).background(colors.onSurface, CircleShape))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
                    }
                    Spacer(Modifier.height(2.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(21.dp), tint = colors.primary)
                            Box(Modifier.width(46.dp).height(5.dp).background(colors.onSurface, CircleShape))
                            Box(Modifier.fillMaxWidth().height(16.dp).background(colors.primary, CircleShape))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(2) {
                            Box(Modifier.weight(1f).height(30.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(9.dp)))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(24.dp).background(colors.surfaceContainer, RoundedCornerShape(8.dp)))
                    Spacer(Modifier.weight(1f))
                    val floating = settings.bottomBar == BottomBarStyle.FLOATING
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (floating) 3.dp else 0.dp)
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(if (floating) 16.dp else 3.dp)).padding(5.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        settings.visibleTabs.forEach { tab ->
                            Icon(tab.icon, null, Modifier.size(13.dp), tint = if (tab == dev.rain.laundry.core.NavigationTab.SETTINGS) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
