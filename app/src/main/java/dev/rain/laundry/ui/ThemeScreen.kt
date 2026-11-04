package dev.rain.laundry.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit, modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 28.dp) {
    val colors = MaterialTheme.colorScheme
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicActive = dynamicSupported && settings.dynamicColor
    val wallpaper = LocalWallpaperColorInfo.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = contentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "preview") { ThemePreview(settings) }

        item(key = "mode") {
            SettingGroup("主题模式", Icons.Outlined.Brightness6) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingDivider()
                    ChoiceRow(mode.title, when (mode) {
                        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Outlined.LightMode
                        ThemeMode.DARK -> Icons.Outlined.DarkMode
                    }, settings.mode == mode, { onChange(settings.copy(mode = mode)) })
                }
            }
        }

        item(key = "black") {
            SettingGroup("深色外观", Icons.Outlined.Contrast) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Nightlight, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("纯黑模式", style = MaterialTheme.typography.bodyLarge)
                        Text("仅在深色主题下生效", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(settings.pureBlack, { onChange(settings.copy(pureBlack = it)) },
                        modifier = Modifier.semantics { contentDescription = "纯黑模式" })
                }
            }
        }

        item(key = "palette") {
            SettingGroup("主题色彩", Icons.Outlined.Palette) {
                FlowRow(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemePalette.entries.forEach { palette ->
                        val chosen = !dynamicActive && settings.palette == palette
                        Box(Modifier.size(48.dp).alpha(if (dynamicActive) .4f else 1f).clip(CircleShape)
                            .selectable(chosen, enabled = !dynamicActive, role = Role.RadioButton, onClick = { onChange(settings.copy(palette = palette)) })
                            .semantics { contentDescription = "${palette.title}配色" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(40.dp).then(if (chosen) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier)
                                .padding(4.dp).background(palette.accent(), CircleShape), contentAlignment = Alignment.Center) {
                                if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(19.dp), tint = Color.White)
                            }
                        }
                    }
                }
                Text(when {
                    !dynamicActive -> "当前配色 · ${settings.palette.title}"
                    wallpaper.seed != null -> "当前配色 · 桌面壁纸"
                    wallpaper.loading -> "当前配色 · 正在取色"
                    else -> "当前配色 · 系统配色（备用）"
                },
                    Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.primary)
                SettingDivider()
                var styleMenu by remember { mutableStateOf(false) }
                val menuScale = animateFloatAsState(if (styleMenu) .982f else 1f,
                    spring(dampingRatio = .5f, stiffness = 600f), label = "styleAnchor")
                Box {
                    Row(Modifier.fillMaxWidth().alpha(if (dynamicActive) .4f else 1f)
                        .graphicsLayer { scaleX = menuScale.value; scaleY = menuScale.value }
                        .clickable(enabled = !dynamicActive) { styleMenu = true }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("配色风格", Modifier.weight(1f))
                        Text(if (dynamicActive) "自动" else settings.colorStyle.title, color = colors.onSurfaceVariant)
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
            SettingGroup("动态配色", Icons.Outlined.Wallpaper) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("从壁纸取色", style = MaterialTheme.typography.bodyLarge)
                        Text(when {
                            !dynamicSupported -> "需要 Android 12 及以上系统"
                            dynamicActive -> "根据桌面壁纸生成主题，关闭后恢复手动色板"
                            else -> "自动使用桌面壁纸的代表色"
                        }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(checked = dynamicActive, enabled = dynamicSupported,
                        onCheckedChange = { onChange(settings.copy(dynamicColor = it)) },
                        modifier = Modifier.semantics { contentDescription = "从壁纸取色" })
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
                            wallpaper.loading -> "正在读取壁纸颜色…"
                            wallpaper.seed != null -> "壁纸颜色已应用"
                            else -> "未获取到壁纸颜色，暂用系统配色"
                        }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        TextButton(onClick = wallpaper.refresh, enabled = !wallpaper.loading) {
                            Text("重新取色")
                        }
                    }
                }
            }
        }

        item(key = "navigation") {
            SettingGroup("底栏", Icons.Outlined.Layers) {
                ChoiceRow("悬浮", Icons.Outlined.ViewAgenda, settings.bottomBar == BottomBarStyle.FLOATING,
                    { onChange(settings.copy(bottomBar = BottomBarStyle.FLOATING)) }, "玻璃强度 · ${settings.glassLevel.title}")
                SettingDivider()
                ChoiceRow("固定", Icons.Outlined.VerticalAlignBottom, settings.bottomBar == BottomBarStyle.FIXED,
                    { onChange(settings.copy(bottomBar = BottomBarStyle.FIXED)) }, "Material 导航栏")
            }
        }

        item(key = "glass") {
            SettingGroup("液态玻璃", Icons.Outlined.WaterDrop) {
                GlassLevel.entries.forEachIndexed { index, level ->
                    if (index > 0) SettingDivider()
                    ChoiceRow(level.title, when (level) {
                        GlassLevel.NONE -> Icons.Outlined.LayersClear
                        GlassLevel.LIGHT -> Icons.Outlined.BlurOn
                        GlassLevel.FULL -> Icons.Outlined.WaterDrop
                    }, settings.glassLevel == level, { onChange(settings.copy(glassLevel = level)) }, when (level) {
                        GlassLevel.NONE -> "实色底栏，保留弹性滑块"
                        GlassLevel.LIGHT -> "柔和毛玻璃，轻微折射与形变"
                        GlassLevel.FULL -> "通透玻璃，更明显的折射与弹性形变"
                    })
                }
                SettingDivider()
                Text(if (settings.bottomBar == BottomBarStyle.FIXED)
                    "当前为固定底栏，不使用液态玻璃。所选等级会在切回悬浮底栏后生效。"
                    else "长按或拖动体验液态形变，轻点切换带弹性回弹。",
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                GlassStylePreview(settings.glassLevel, settings.visibleTabs)
            }
        }

        item(key = "swipe") {
            SettingGroup("页面切换", Icons.Outlined.Swipe) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Swipe, null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("左右滑动翻页", style = MaterialTheme.typography.bodyLarge)
                        Text("在底栏当前显示的页面之间切换", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(settings.swipeNavigation, { onChange(settings.copy(swipeNavigation = it)) },
                        modifier = Modifier.semantics { contentDescription = "左右滑动翻页" })
                }
            }
        }

        item(key = "animation") {
            SettingGroup("选项卡动画", Icons.Outlined.Animation) {
                PageAnimation.entries.forEachIndexed { index, animation ->
                    if (index > 0) SettingDivider()
                    ChoiceRow(animation.title, when (animation) {
                        PageAnimation.SLIDE -> Icons.Outlined.Swipe
                        PageAnimation.FADE -> Icons.Outlined.BlurOn
                        PageAnimation.NONE -> Icons.Outlined.MotionPhotosOff
                    }, settings.animation == animation, { onChange(settings.copy(animation = animation)) })
                }
            }
        }
        item(key = "savedHint") {
            Text("更改即时生效，并自动保存。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun ChoiceRow(title: String, icon: ImageVector, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .974f else 1f,
        spring(dampingRatio = .48f, stiffness = 650f), label = "choicePress")
    val iconColor by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        spring(stiffness = 500f), label = "choiceColor")
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .selectable(selected, interactionSource = interaction, indication = LocalIndication.current,
            onClick = onSelect, role = Role.RadioButton).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(34.dp).background(iconColor,
            RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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

/** A live miniature of this app; all colors and bar layout follow the real settings. */
@Composable
private fun ThemePreview(settings: ThemeSettings) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().height(264.dp).clearAndSetSemantics { contentDescription = "当前主题预览" },
        shape = RoundedCornerShape(30.dp), color = colors.surfaceContainer) {
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
