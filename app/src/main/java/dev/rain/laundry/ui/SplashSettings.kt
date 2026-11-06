package dev.rain.laundry.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SplashSettings(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit) {
    val context = LocalContext.current
    val imageStore = remember(context) { SplashImageStore(context) }
    val scope = rememberCoroutineScope()
    val latestSettings by rememberUpdatedState(settings)
    val latestOnChange by rememberUpdatedState(onChange)
    var addingImages by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewImage by rememberSaveable { mutableStateOf<String?>(null) }
    var durationDraft by rememberSaveable(settings.splashDurationMs) { mutableStateOf(settings.splashDurationMs.toString()) }
    var durationError by rememberSaveable(settings.splashDurationMs) { mutableStateOf<String?>(null) }
    var durationFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val images = effectiveSplashImages(settings.splashImages)
    val custom = settings.splashImages.isNotEmpty()
    val colors = MaterialTheme.colorScheme

    fun saveDuration(): Boolean {
        val duration = durationDraft.toIntOrNull()
        if (duration == null || duration !in MIN_SPLASH_DURATION_MS..MAX_SPLASH_DURATION_MS) {
            durationError = "请输入 $MIN_SPLASH_DURATION_MS 至 $MAX_SPLASH_DURATION_MS 之间的整数（ms）"
            return false
        }
        durationError = null
        if (duration != latestSettings.splashDurationMs) {
            latestOnChange(latestSettings.copy(splashDurationMs = duration))
        }
        durationDraft = duration.toString()
        return true
    }

    fun toggleExpanded() {
        if (expanded) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
        expanded = !expanded
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !importing) {
            val append = addingImages
            val existing = if (append) latestSettings.splashImages else emptyList()
            if (existing.size + uris.size > MAX_SPLASH_IMAGES) {
                error = "最多使用 $MAX_SPLASH_IMAGES 张图片，请减少选择数量。"
            } else {
                importing = true
                error = null
                scope.launch {
                    try {
                        val imported = imageStore.importImages(uris)
                        // Read the latest theme after the picker and IO work return.
                        latestOnChange(latestSettings.copy(splashImages = existing + imported))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: IOException) {
                        error = "${failure.message ?: "图片导入失败"}。原有图片已保留。"
                    } catch (_: Exception) {
                        error = "图片导入失败，请选择有效的图片后重试。原有图片已保留。"
                    } finally {
                        importing = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f)
                .clickable(onClickLabel = if (expanded) "收起开屏设置" else "展开开屏设置", onClick = ::toggleExpanded)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("显示开屏看板娘", style = MaterialTheme.typography.bodyLarge)
                Text("每次启动显示一张，下次启动切换下一张",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = settings.splashEnabled, enabled = !importing,
                onCheckedChange = { latestOnChange(latestSettings.copy(splashEnabled = it)) },
                modifier = Modifier.semantics { contentDescription = "显示开屏看板娘" })
            IconButton(onClick = ::toggleExpanded) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起开屏设置" else "展开开屏设置")
            }
        }

        AnimatedVisibility(visible = expanded,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = .55f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = durationDraft,
                        onValueChange = { durationDraft = it; durationError = null },
                        label = { Text("开屏时长（ms）") },
                        supportingText = {
                            Text(durationError ?: "默认 $DEFAULT_SPLASH_DURATION_MS ms，范围 $MIN_SPLASH_DURATION_MS–$MAX_SPLASH_DURATION_MS ms")
                        },
                        isError = durationError != null, enabled = !importing, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (saveDuration()) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                            if (durationFocused && !focus.isFocused) saveDuration()
                            durationFocused = focus.isFocused
                        })
                    TextButton(onClick = {
                        if (saveDuration()) {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }, enabled = !importing) { Text("保存时长") }
                }

                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = .55f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (custom) "自定义图片" else "默认图片"} · ${images.size} 张",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    TextButton(onClick = { previewImage = images.first() }, enabled = !importing) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("预览开屏")
                    }
                }

                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(images, key = { _, source -> source }) { index, source ->
                        Column(Modifier.width(144.dp)) {
                            val bitmap by rememberSplashBitmap(source)
                            Box(Modifier.fillMaxWidth().aspectRatio(.62f).clip(RoundedCornerShape(16.dp))
                                .background(colors.background)
                                .clickable(enabled = !importing) { previewImage = source }, contentAlignment = Alignment.Center) {
                                bitmap?.let {
                                    Image(it, contentDescription = "开屏图片 ${index + 1}",
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            if (custom) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    IconButton(onClick = {
                                        val reordered = latestSettings.splashImages.toMutableList()
                                        val currentIndex = reordered.indexOf(source)
                                        if (currentIndex in 1 until reordered.size) {
                                            val moved = reordered.removeAt(currentIndex)
                                            reordered.add(currentIndex - 1, moved)
                                            latestOnChange(latestSettings.copy(splashImages = reordered))
                                        }
                                    }, enabled = !importing && index > 0, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.ArrowBack, "第 ${index + 1} 张向前移动", Modifier.size(19.dp))
                                    }
                                    IconButton(onClick = {
                                        latestOnChange(latestSettings.copy(splashImages = latestSettings.splashImages.filterNot { it == source }))
                                    }, enabled = !importing, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.Close, "移除第 ${index + 1} 张图片", Modifier.size(19.dp))
                                    }
                                    IconButton(onClick = {
                                        val reordered = latestSettings.splashImages.toMutableList()
                                        val currentIndex = reordered.indexOf(source)
                                        if (currentIndex >= 0 && currentIndex < reordered.lastIndex) {
                                            val moved = reordered.removeAt(currentIndex)
                                            reordered.add(currentIndex + 1, moved)
                                            latestOnChange(latestSettings.copy(splashImages = reordered))
                                        }
                                    }, enabled = !importing && index < images.lastIndex, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.ArrowForward, "第 ${index + 1} 张向后移动", Modifier.size(19.dp))
                                    }
                                }
                            } else {
                                Text("${index + 1}", Modifier.padding(vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }

                Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (custom) "按当前顺序在每次启动时切换，可调整顺序或移除图片。移除全部后恢复默认。"
                        else "选择自己的图片替换默认看板娘。多张图片按选择顺序在每次启动时切换。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(onClick = {
                            addingImages = custom
                            error = null
                            picker.launch(arrayOf("image/*"))
                        }, enabled = !importing && (!custom || images.size < MAX_SPLASH_IMAGES)) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (custom) "添加图片" else "自定义图片")
                        }
                        if (custom) {
                            TextButton(onClick = {
                                error = null
                                latestOnChange(latestSettings.copy(splashImages = emptyList()))
                            }, enabled = !importing) { Text("恢复默认") }
                        }
                    }
                    if (custom) {
                        TextButton(onClick = {
                            addingImages = false
                            error = null
                            picker.launch(arrayOf("image/*"))
                        }, enabled = !importing, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text("重新选择图片")
                        }
                    }
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在保存图片…", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else {
                        Text("最多 $MAX_SPLASH_IMAGES 张，图片保存在本机。关闭开屏仍会保留图片设置。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    error?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = colors.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }

    previewImage?.let { source ->
        Dialog(onDismissRequest = { previewImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            // A Compose Dialog owns a separate window; its context may wrap the Activity.
            val dialogView = LocalView.current
            val darkTheme = LocalLaundryDarkTheme.current
            DisposableEffect(dialogView, darkTheme) {
                val window = (dialogView.parent as? DialogWindowProvider)?.window
                val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
                val previousStatus = controller?.isAppearanceLightStatusBars
                val previousNavigation = controller?.isAppearanceLightNavigationBars
                controller?.isAppearanceLightStatusBars = !darkTheme
                controller?.isAppearanceLightNavigationBars = !darkTheme
                onDispose {
                    previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
                    previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
                }
            }
            SplashScreen(image = source, durationMs = settings.splashDurationMs,
                onFinished = { previewImage = null }, modifier = Modifier.fillMaxSize())
        }
    }
}
