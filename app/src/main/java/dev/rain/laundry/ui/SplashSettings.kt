package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
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
import dev.rain.laundry.ui.ControlIcon as Icon
import dev.rain.laundry.i18n.LocalizedText as Text
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
            durationError = appText(R.string.msg_e8d09706ad21 , MIN_SPLASH_DURATION_MS, MAX_SPLASH_DURATION_MS)
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
                error = appText(R.string.msg_569001c47283 , MAX_SPLASH_IMAGES)
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
                        error = appText(R.string.msg_5ecee348e4b3 , failure.message ?: appText(R.string.msg_12515926f77d))
                    } catch (_: Exception) {
                        error = appText(R.string.msg_298186c3f519)
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
                .clickable(onClickLabel = if (expanded) appText(R.string.msg_2d264ece4497) else appText(R.string.msg_d00503584e15), onClick = ::toggleExpanded)
                .semantics { stateDescription = if (expanded) appText(R.string.msg_df647b73b990) else appText(R.string.msg_e64447db76a9) },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText(R.string.msg_63109ff6f1bb), style = MaterialTheme.typography.bodyLarge)
            }
            AppSwitch(checked = settings.splashEnabled, enabled = !importing,
                onCheckedChange = { latestOnChange(latestSettings.copy(splashEnabled = it)) },
                modifier = Modifier.semantics { contentDescription = appText(R.string.msg_63109ff6f1bb) })
            AppIconButton(onClick = ::toggleExpanded) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) appText(R.string.msg_2d264ece4497) else appText(R.string.msg_d00503584e15))
            }
        }

        AnimatedVisibility(visible = expanded,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = .55f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedTextField(value = durationDraft,
                        onValueChange = { durationDraft = it; durationError = null },
                        labelText = appText(R.string.msg_7647946e9f07),
                        label = { Text(appText(R.string.msg_7647946e9f07)) },
                        supportingText = {
                            Text(durationError ?: appText(R.string.msg_6978239af3fb , DEFAULT_SPLASH_DURATION_MS, MIN_SPLASH_DURATION_MS, MAX_SPLASH_DURATION_MS))
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
                    AppTextButton(onClick = {
                        if (saveDuration()) {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }, enabled = !importing) { Text(appText(R.string.msg_793dc584cf9d)) }
                }

                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = .55f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(appText(R.string.msg_475091a94a64 , if (custom) appText(R.string.msg_0dc111847321) else appText(R.string.msg_6f10af9918f8), images.size),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    AppTextButton(onClick = { previewImage = images.first() }, enabled = !importing) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appText(R.string.msg_b396db2e9196))
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
                                    Image(it, contentDescription = appText(R.string.msg_91a1254d600f , index + 1),
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            if (custom) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    AppIconButton(onClick = {
                                        val reordered = latestSettings.splashImages.toMutableList()
                                        val currentIndex = reordered.indexOf(source)
                                        if (currentIndex in 1 until reordered.size) {
                                            val moved = reordered.removeAt(currentIndex)
                                            reordered.add(currentIndex - 1, moved)
                                            latestOnChange(latestSettings.copy(splashImages = reordered))
                                        }
                                    }, enabled = !importing && index > 0, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.ArrowBack, appText(R.string.msg_0b2f1558771b , index + 1), Modifier.size(19.dp))
                                    }
                                    AppIconButton(onClick = {
                                        latestOnChange(latestSettings.copy(splashImages = latestSettings.splashImages.filterNot { it == source }))
                                    }, enabled = !importing, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.Close, appText(R.string.msg_1fd81704a521 , index + 1), Modifier.size(19.dp))
                                    }
                                    AppIconButton(onClick = {
                                        val reordered = latestSettings.splashImages.toMutableList()
                                        val currentIndex = reordered.indexOf(source)
                                        if (currentIndex >= 0 && currentIndex < reordered.lastIndex) {
                                            val moved = reordered.removeAt(currentIndex)
                                            reordered.add(currentIndex + 1, moved)
                                            latestOnChange(latestSettings.copy(splashImages = reordered))
                                        }
                                    }, enabled = !importing && index < images.lastIndex, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.ArrowForward, appText(R.string.msg_3465dd4a34e0 , index + 1), Modifier.size(19.dp))
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
                    Text(if (custom) appText(R.string.msg_d6aaf25dfadf)
                        else appText(R.string.msg_6303308108a0),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppButton(controlId = "theme.splash.images.add", onClick = {
                            addingImages = custom
                            error = null
                            picker.launch(arrayOf("image/*"))
                        }, colors = ButtonDefaults.filledTonalButtonColors(),
                            enabled = !importing && (!custom || images.size < MAX_SPLASH_IMAGES)) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (custom) appText(R.string.msg_9f0d4e4656ee) else appText(R.string.msg_0dc111847321))
                        }
                        if (custom) {
                            AppTextButton(onClick = {
                                error = null
                                latestOnChange(latestSettings.copy(splashImages = emptyList()))
                            }, enabled = !importing) { Text(appText(R.string.msg_ba2e93e73037)) }
                        }
                    }
                    if (custom) {
                        AppTextButton(onClick = {
                            addingImages = false
                            error = null
                            picker.launch(arrayOf("image/*"))
                        }, enabled = !importing, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(appText(R.string.msg_7f48dd3aa586))
                        }
                    }
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(appText(R.string.msg_9d2e3d5ad46b), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else {
                        Text(appText(R.string.msg_a74a462db571 , MAX_SPLASH_IMAGES),
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
