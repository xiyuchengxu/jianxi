package dev.rain.laundry.ui

import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Elastic selection is shared by all levels; optical work only runs for glass levels. */
@Composable
fun GlassNavigationBar(
    layer: GraphicsLayer,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glassLevel: GlassLevel = GlassLevel.FULL,
    backdropOrigin: Offset = Offset.Zero
) {
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }
    val origin = positionInRoot - backdropOrigin
    var pressed by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    val pointer = remember { mutableFloatStateOf(0f) }
    var previewIndex by remember { mutableIntStateOf(selected) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dark = LocalLaundryDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val scene = rememberGraphicsLayer()
    val optical = glassLevel != GlassLevel.NONE
    val scope = rememberCoroutineScope()
    val tapPulse = remember { Animatable(0f) }
    fun select(index: Int) {
        if (index != currentSelected) scope.launch {
            tapPulse.snapTo(1f)
            tapPulse.animateTo(0f, spring(dampingRatio = .43f, stiffness = 580f))
        }
        currentOnSelect(index)
    }
    val lens = remember(glassLevel) {
        if (optical && Build.VERSION.SDK_INT >= 33) runCatching { GlassLensEffect() }.getOrNull() else null
    }
    val backdropBlur = remember(glassLevel, density) {
        if (optical && Build.VERSION.SDK_INT >= 31) {
            val radius = with(density) { (if (glassLevel == GlassLevel.LIGHT) 7.dp else 2.dp).toPx() }
            android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else null
    }
    // Keep animation reads out of this composition and the navigation labels.
    val pressure = animateFloatAsState(if (pressed) 1f else 0f,
        spring(dampingRatio = if (pressed) .48f else .82f, stiffness = if (pressed) 460f else 600f), label = "glassPressure")
    val touchPressure = animateFloatAsState(if (touching) 1f else 0f,
        spring(dampingRatio = .52f, stiffness = 650f), label = "barTouch")
    val lensVisible by remember { derivedStateOf { pressure.value > .01f } }
    val showLens = optical && lensVisible
    val trackShape = RoundedCornerShape(30.dp)
    val trackTint = remember(scheme, dark) { Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) .09f else .38f), Color.Transparent, scheme.primary.copy(alpha = .035f)
    )) }
    val trackBorder = remember(scheme, dark) { Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) .30f else .78f), scheme.outlineVariant.copy(alpha = .20f), Color.White.copy(alpha = .30f)
    )) }

    BoxWithConstraints(modifier.widthIn(max = 312.dp).fillMaxWidth().height(84.dp)
        .onGloballyPositioned { positionInRoot = it.positionInRoot() }) {
        val widthPx = with(density) { maxWidth.toPx() }
        val hostHeight = with(density) { 84.dp.toPx() }
        val trackTop = with(density) { 12.dp.toPx() }
        val paddingPx = with(density) { 5.dp.toPx() }
        val tabCount = laundryTabs.size
        val slot = (widthPx - paddingPx * 2) / tabCount
        fun indexAt(x: Float) = ((x - paddingPx) / slot).toInt().coerceIn(0, tabCount - 1)
        fun center(index: Int) = paddingPx + slot * (index + .5f)
        val desiredCenter = if (pressed) pointer.floatValue.coerceIn(center(0) - 5f, center(tabCount - 1) + 5f) else center(selected)
        val animatedCenter = animateFloatAsState(desiredCenter,
            spring(dampingRatio = if (pressed) .69f else .51f, stiffness = if (pressed) 760f else 430f), label = "glassPosition")
        val visualCenter = remember(animatedCenter, widthPx) {
            derivedStateOf { animatedCenter.value.coerceIn(center(0), center(tabCount - 1)) }
        }
        val active = if (pressed) previewIndex else selected

        Box(Modifier.fillMaxWidth().height(60.dp).align(Alignment.Center)
            .shadow(12.dp, trackShape, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .14f))
            .drawWithContent {
                if (showLens) {
                    scene.record { this@drawWithContent.drawContent() }
                    drawLayer(scene)
                } else drawContent()
            }
            .clip(trackShape)
            .pointerInput(enabled, widthPx, glassLevel) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    val gestureScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touching = true
                        pointer.floatValue = down.position.x
                        previewIndex = indexAt(pointer.floatValue)
                        var cancelled = false
                        // A short tap never starts an offscreen refractive rendering pass.
                        val hold = gestureScope.launch { delay(140); pressed = true }
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                pointer.floatValue = change.position.x.coerceIn(0f, widthPx)
                                if (change.position.y < -size.height || change.position.y > size.height * 2) cancelled = true
                                if (change.pressed && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    hold.cancel()
                                    pressed = true
                                }
                                val next = indexAt(pointer.floatValue)
                                if (previewIndex != next) {
                                    previewIndex = next
                                    if (pressed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                change.consume()
                                if (!change.pressed) {
                                    if (!cancelled) select(previewIndex)
                                    break
                                }
                            }
                        } finally {
                            hold.cancel()
                            pressed = false
                            touching = false
                            previewIndex = currentSelected
                        }
                    }
                }
            }) {
            if (optical) Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = backdropBlur }) {
                withTransform({ translate(-origin.x, -origin.y - trackTop) }) { drawLayer(layer) }
            }
            val surfaceAlpha = when {
                !optical -> 1f
                Build.VERSION.SDK_INT < 31 -> .94f
                glassLevel == GlassLevel.LIGHT -> if (dark) .76f else .80f
                else -> if (dark) .30f else .42f
            }
            Box(Modifier.matchParentSize().background(
                (if (optical) scheme.surface else scheme.surfaceContainerHigh).copy(alpha = surfaceAlpha)))
            if (optical) Box(Modifier.matchParentSize().background(trackTint).border(.8.dp, trackBorder, trackShape))
            else Box(Modifier.matchParentSize().border(.8.dp, scheme.outlineVariant.copy(alpha = .55f), trackShape))
            Box(Modifier.offset { IntOffset((visualCenter.value - slot / 2).roundToInt(), paddingPx.roundToInt()) }
                .width(with(density) { slot.toDp() }).height(50.dp)
                .graphicsLayer {
                    alpha = if (optical) 1f - pressure.value.coerceIn(0f, 1f) else 1f
                    val lag = (abs(desiredCenter - animatedCenter.value) / slot).coerceIn(0f, 1f)
                    scaleX = 1f + .15f * lag + .045f * touchPressure.value - .075f * tapPulse.value
                    scaleY = 1f - .08f * lag - .07f * touchPressure.value + .065f * tapPulse.value
                }
                .background(scheme.primary.copy(alpha = if (dark) .15f else .105f), RoundedCornerShape(25.dp)))
            Row(Modifier.fillMaxSize().padding(5.dp)) {
                laundryTabs.forEachIndexed { index, (title, icon) ->
                    val color = if (active == index) scheme.primary else scheme.onSurfaceVariant
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(25.dp))
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            this.selected = selected == index
                            if (!enabled) disabled()
                            onClick(label = title) {
                                if (enabled) { select(index); true } else false
                            }
                        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(title, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (showLens) GlassLensOverlay(layer, scene, pressure, visualCenter, pointer,
            slot, hostHeight, trackTop, origin, lens, glassLevel)
    }
}

/** Only this small subtree recomposes while the lens grows or settles. */
@Composable
private fun GlassLensOverlay(
    layer: GraphicsLayer,
    scene: GraphicsLayer,
    pressure: State<Float>,
    centerPosition: State<Float>,
    pointer: State<Float>,
    slot: Float,
    hostHeight: Float,
    trackTop: Float,
    origin: Offset,
    effect: GlassLensEffect?,
    level: GlassLevel
) {
    val density = LocalDensity.current
    // Keep a little spring overshoot in the silhouette; shader/alpha remain bounded.
    val amount = pressure.value.coerceIn(0f, 1.14f)
    val strength = if (level == GlassLevel.LIGHT) .32f else 1f
    val growth = if (level == GlassLevel.LIGHT) .38f else 1f
    val animatedCenter = centerPosition.value
    val pointerX = pointer.value
    val lag = ((pointerX - animatedCenter) / slot).coerceIn(-1f, 1f)
    val stretch = abs(lag) * amount
    val lensWidth = slot * (1f + .11f * amount * growth + .17f * stretch * growth)
    val lensHeight = with(density) { (50.dp + 30.dp * amount * growth - 7.dp * stretch * growth).toPx() }
    val lensLeft = animatedCenter - lensWidth / 2
    val lensTop = (hostHeight - lensHeight) / 2
    val border = remember { Brush.sweepGradient(listOf(
        Color(0xFFA2BDFF).copy(alpha = .54f), Color.White.copy(alpha = .8f),
        Color(0xFF9AE9CE).copy(alpha = .30f), Color(0xFFE3AE85).copy(alpha = .42f), Color(0xFFA2BDFF).copy(alpha = .54f)
    )) }
    val tint = remember { Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .10f))) }
    Box(Modifier.offset { IntOffset(lensLeft.roundToInt(), lensTop.roundToInt()) }
        .requiredSize(with(density) { lensWidth.toDp() }, with(density) { lensHeight.toDp() })
        .graphicsLayer {
            alpha = (amount * 1.8f).coerceIn(0f, 1f)
            rotationZ = lag * amount * growth * 3f
        }
        .shadow(15.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .16f), spotColor = Color.Black.copy(alpha = .25f))
        .clip(CircleShape)) {
        Canvas(Modifier.matchParentSize().graphicsLayer {
            if (Build.VERSION.SDK_INT >= 33 && effect != null) {
                renderEffect = effect.configure(size.width, size.height, density.density, amount * strength, lag)
            }
        }) {
            val fallbackZoom = if (Build.VERSION.SDK_INT < 33 || effect == null) 1f + .14f * amount * strength else 1f
            withTransform({ scale(fallbackZoom, fallbackZoom, center) }) {
                withTransform({ translate(-origin.x - lensLeft, -origin.y - lensTop) }) { drawLayer(layer) }
                withTransform({ translate(-lensLeft, trackTop - lensTop) }) { drawLayer(scene) }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val inset = .8.dp.toPx()
            val borderSize = Size(size.width - inset * 2, size.height - inset * 2)
            val corner = androidx.compose.ui.geometry.CornerRadius(borderSize.height / 2)
            drawRoundRect(border, topLeft = Offset(inset, inset), size = borderSize, cornerRadius = corner,
                alpha = amount.coerceIn(0f, 1f), style = Stroke(1.15.dp.toPx()))
            drawRoundRect(tint, topLeft = Offset(inset, inset), size = borderSize, cornerRadius = corner, alpha = amount.coerceIn(0f, 1f))
        }
    }
}
