package dev.rain.laundry.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults as MiuixCheckboxDefaults
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults as MiuixSwitchDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SmoothRoundedCornerShape

@Composable
private fun miuixNeutralBorder(explicit: BorderStroke? = null): BorderStroke? = explicit
    ?: if (LocalLaundryDarkTheme.current) BorderStroke(1.dp, MiuixTheme.colorScheme.outline) else null

// An originally unfilled control gains a local fill only when explicitly customized.
@Composable
private fun Color.buttonFill(): Color = if (LocalControlHasOverride.current && alpha == 0f)
    MaterialTheme.colorScheme.surfaceContainerHigh.controlContainer() else controlContainer()

@Composable
internal fun ButtonColors.withControlOpacity(): ButtonColors = copy(
    containerColor = containerColor.buttonFill(), disabledContainerColor = disabledContainerColor.buttonFill()
)

@Composable
private fun CardColors.withControlOpacity(): CardColors =
    if (LocalControlOpacity.current == 1f) this else copy(
        containerColor = containerColor.controlContainer(),
        disabledContainerColor = disabledContainerColor.controlContainer()
    )

internal val LocalControlGlyphOutline = staticCompositionLocalOf { false }

@Composable
private fun ControlContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalControlGlyphOutline provides (LocalControlOpacity.current <= .5f), content = content)
}

@Composable
internal fun Modifier.controlGlyphContrast(foreground: Color): Modifier =
    if (LocalControlGlyphOutline.current) controlForegroundOutline(foreground) else this

@Composable
internal fun ControlIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    Icon(imageVector, contentDescription, modifier.controlGlyphContrast(tint), tint = tint)
}

// Fallback for fixed controls with one call site. Repeated business rows supply semantic IDs.
@Composable
private fun siteControlId(kind: String): String = "$kind.${currentCompositeKeyHash.toUInt().toString(16)}"

@Composable
internal fun AdjustableSettingRow(controlId: String, modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top, content: @Composable RowScope.() -> Unit) {
    AdjustableControl(controlId, Modifier) { target ->
        val fill = if (LocalControlHasOverride.current)
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.controlContainer()) else Modifier
        ControlContent { Row(target.then(fill).then(modifier), horizontalArrangement, verticalAlignment, content) }
    }
}

@Composable
internal fun AdjustableSurface(controlId: String, modifier: Modifier = Modifier, shape: Shape = RectangleShape,
    color: Color, contentColor: Color = contentColorFor(color), content: @Composable () -> Unit) {
    AdjustableControl(controlId, modifier) { target ->
        Surface(target, shape = shape, color = color.controlContainer(), contentColor = contentColor) {
            ControlContent(content)
        }
    }
}

/** Long-lived list controls need an identity independent of order or user-visible labels. */
@Composable
internal fun privateControlId(kind: String, identity: String): String = remember(kind, identity) {
    "$kind.${dev.rain.laundry.core.WireProtocol.sha256(identity).take(24)}"
}

/** Shared component entry points select the installed UI toolkit, retaining Material as a preset. */
@Composable
internal fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    controlId: String = siteControlId("button"),
    content: @Composable RowScope.() -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        val buttonColors = colors.withControlOpacity()
        val buttonContent: @Composable RowScope.() -> Unit = { ControlContent { content() } }
        if (LocalMiuixStyle.current) {
            MiuixButton(onClick, controlModifier, enabled,
                colors = MiuixButtonDefaults.buttonColors(buttonColors.containerColor, buttonColors.disabledContainerColor)) {
                CompositionLocalProvider(LocalContentColor provides
                    if (enabled) colors.contentColor else colors.disabledContentColor,
                    LocalTextStyle provides MaterialTheme.typography.labelLarge) { buttonContent() }
            }
        } else androidx.compose.material3.Button(onClick, controlModifier, enabled, shape = shape, colors = buttonColors,
            contentPadding = contentPadding, content = buttonContent)
    }
}

@Composable
internal fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    controlId: String = siteControlId("outlinedbutton"),
    content: @Composable RowScope.() -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        val buttonContent: @Composable RowScope.() -> Unit = { ControlContent { content() } }
        if (LocalMiuixStyle.current) {
            val edge = miuixNeutralBorder()
            val outline = if (edge != null) Modifier.border(edge, SmoothRoundedCornerShape(16.dp)) else Modifier
            MiuixButton(onClick, controlModifier.then(outline), enabled, colors = MiuixButtonDefaults.buttonColors(
                MiuixTheme.colorScheme.secondaryVariant.controlContainer(),
                MiuixTheme.colorScheme.disabledSecondaryVariant.controlContainer())) {
                CompositionLocalProvider(LocalContentColor provides if (enabled)
                    MiuixTheme.colorScheme.onSecondaryVariant else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    LocalTextStyle provides MaterialTheme.typography.labelLarge) { buttonContent() }
            }
        } else androidx.compose.material3.OutlinedButton(onClick, controlModifier, enabled, shape = shape, colors = colors.withControlOpacity(),
            contentPadding = contentPadding, content = buttonContent)
    }
}

@Composable
internal fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    controlId: String = siteControlId("textbutton"),
    content: @Composable RowScope.() -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        if (LocalMiuixStyle.current) {
            MiuixButton(onClick, controlModifier, enabled,
                colors = MiuixButtonDefaults.buttonColors(Color.Transparent.buttonFill(), Color.Transparent.buttonFill()),
                insideMargin = contentPadding) {
                CompositionLocalProvider(LocalContentColor provides
                    if (enabled) colors.contentColor else colors.disabledContentColor,
                    LocalTextStyle provides MaterialTheme.typography.labelLarge) { content() }
            }
        } else androidx.compose.material3.TextButton(onClick, controlModifier, enabled, shape = shape, colors = colors.withControlOpacity(),
            contentPadding = contentPadding, content = content)
    }
}

@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    controlId: String = siteControlId("card"),
    content: @Composable ColumnScope.() -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        val cardContent: @Composable ColumnScope.() -> Unit = { ControlContent { content() } }
        if (LocalMiuixStyle.current) {
            val edge = miuixNeutralBorder(border)
            val outline = if (edge != null) Modifier.border(edge, SmoothRoundedCornerShape(16.dp)) else Modifier
            MiuixCard(controlModifier.then(outline), color = colors.containerColor.controlContainer()) {
                CompositionLocalProvider(LocalContentColor provides colors.contentColor) { cardContent() }
            }
        } else androidx.compose.material3.Card(controlModifier, shape, colors.withControlOpacity(), elevation, border, cardContent)
    }
}

@Composable
internal fun AppOutlinedCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke? = null,
    controlId: String = siteControlId("outlinedcard"),
    content: @Composable ColumnScope.() -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        val cardContent: @Composable ColumnScope.() -> Unit = { ControlContent { content() } }
        if (LocalMiuixStyle.current) {
            val nativeShape = SmoothRoundedCornerShape(16.dp)
            val interaction = if (onClick != null) Modifier.clip(nativeShape).clickable(enabled = enabled, onClick = onClick)
                else Modifier
            val edge = miuixNeutralBorder(border)
            val outline = if (edge != null) Modifier.border(edge, nativeShape) else Modifier
            MiuixCard(controlModifier.then(outline).then(interaction).alpha(if (enabled) 1f else .6f),
                color = (if (enabled) colors.containerColor else colors.disabledContainerColor).controlContainer()) {
                CompositionLocalProvider(LocalContentColor provides
                    if (enabled) colors.contentColor else colors.disabledContentColor) { cardContent() }
            }
        } else if (onClick != null) {
            androidx.compose.material3.OutlinedCard(onClick, controlModifier, enabled, shape, colors.withControlOpacity(), elevation,
                border ?: CardDefaults.outlinedCardBorder(enabled), content = cardContent)
        } else androidx.compose.material3.OutlinedCard(controlModifier, shape, colors.withControlOpacity(), elevation,
            border ?: CardDefaults.outlinedCardBorder(), cardContent)
    }
}

@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    controlId: String = siteControlId("switch")
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        if (LocalMiuixStyle.current) {
            // Retained library drag gestures must observe current permission/busy state too.
            val latestEnabled = rememberUpdatedState(enabled)
            val latestChange = rememberUpdatedState(onCheckedChange)
            MiuixSwitch(checked, if (onCheckedChange == null) null else { value ->
                if (latestEnabled.value) latestChange.value?.invoke(value)
            }, controlModifier, enabled = enabled, colors = MiuixSwitchDefaults.switchColors(
                checkedTrackColor = MiuixTheme.colorScheme.primary.controlContainer(),
                uncheckedTrackColor = MiuixTheme.colorScheme.secondary.controlContainer(),
                disabledCheckedTrackColor = MiuixTheme.colorScheme.disabledPrimary.controlContainer(),
                disabledUncheckedTrackColor = MiuixTheme.colorScheme.disabledSecondary.controlContainer()))
        } else androidx.compose.material3.Switch(checked, onCheckedChange, controlModifier,
            enabled = enabled, colors = colors.copy(
                checkedTrackColor = colors.checkedTrackColor.controlContainer(),
                uncheckedTrackColor = colors.uncheckedTrackColor.controlContainer(),
                disabledCheckedTrackColor = colors.disabledCheckedTrackColor.controlContainer(),
                disabledUncheckedTrackColor = colors.disabledUncheckedTrackColor.controlContainer()))
    }
}

@Composable
internal fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    controlId: String = siteControlId("iconbutton"),
    content: @Composable () -> Unit
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        if (LocalMiuixStyle.current) MiuixIconButton(onClick, controlModifier, enabled,
            backgroundColor = (if (enabled) colors.containerColor else colors.disabledContainerColor).buttonFill()) {
            CompositionLocalProvider(LocalContentColor provides
                if (enabled) colors.contentColor else colors.disabledContentColor, content = content)
        }
        else androidx.compose.material3.IconButton(onClick, controlModifier, enabled, colors = colors.copy(
            containerColor = colors.containerColor.buttonFill(),
            disabledContainerColor = colors.disabledContainerColor.buttonFill()), content = content)
    }
}

@Composable
internal fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    controlId: String = siteControlId("checkbox")
) {
    AdjustableControl(controlId, modifier) { controlModifier ->
        if (LocalMiuixStyle.current) {
            val latestEnabled = rememberUpdatedState(enabled)
            val latestChange = rememberUpdatedState(onCheckedChange)
            MiuixCheckbox(checked, if (onCheckedChange == null) null else { value ->
                if (latestEnabled.value) latestChange.value?.invoke(value)
            }, controlModifier, enabled = enabled, colors = MiuixCheckboxDefaults.checkboxColors(
                checkedBackgroundColor = MiuixTheme.colorScheme.primary.controlContainer(),
                uncheckedBackgroundColor = MiuixTheme.colorScheme.secondary.controlContainer(),
                disabledCheckedBackgroundColor = MiuixTheme.colorScheme.disabledPrimary.controlContainer(),
                disabledUncheckedBackgroundColor = MiuixTheme.colorScheme.disabledSecondary.controlContainer()))
        } else androidx.compose.material3.Checkbox(checked, onCheckedChange, controlModifier,
            enabled = enabled, colors = colors.copy(
                checkedBoxColor = colors.checkedBoxColor.controlContainer(),
                uncheckedBoxColor = colors.uncheckedBoxColor.controlContainer(),
                disabledCheckedBoxColor = colors.disabledCheckedBoxColor.controlContainer(),
                disabledUncheckedBoxColor = colors.disabledUncheckedBoxColor.controlContainer(),
                disabledIndeterminateBoxColor = colors.disabledIndeterminateBoxColor.controlContainer()))
    }
}

/** Explicit label text lets the native Miuix field retain its floating label and edit semantics. */
@Composable
internal fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelText: String,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = androidx.compose.material3.OutlinedTextFieldDefaults.shape,
    controlId: String = siteControlId("input")
) {
    AdjustableControl(controlId, modifier, edgeOnly = true) { controlModifier ->
        // Miuix 0.3.4 has no independent placeholder slot. Preserve forms needing that behavior.
        if (!LocalMiuixStyle.current || placeholder != null) {
            val fieldFill = Color.Transparent.buttonFill()
            androidx.compose.material3.OutlinedTextField(value, onValueChange, controlModifier,
                enabled = enabled, readOnly = readOnly, textStyle = textStyle,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = fieldFill, unfocusedContainerColor = fieldFill,
                    errorContainerColor = fieldFill, disabledContainerColor = fieldFill),
                label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                prefix = prefix, supportingText = supportingText, isError = isError,
                visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
                minLines = minLines, interactionSource = interactionSource, shape = shape)
            return@AdjustableControl
        }
        val native = MiuixTheme.colorScheme
        val errorColor = MaterialTheme.colorScheme.error
        val editingInteraction = interactionSource ?: remember { MutableInteractionSource() }
        val focused by editingInteraction.collectIsFocusedAsState()
        val edge = when {
            isError -> BorderStroke(2.dp, errorColor)
            LocalLaundryDarkTheme.current && !focused -> BorderStroke(1.dp, native.outline)
            else -> null
        }
        val editingModifier = controlModifier.semantics {
            contentDescription = labelText
            if (isError) error(labelText)
        }.then(if (edge != null) Modifier.border(edge, SmoothRoundedCornerShape(16.dp)) else Modifier)
        val contentColor = if (!enabled) native.disabledOnSurface
            else if (textStyle.color == Color.Unspecified) native.onSurface else textStyle.color
        val leadingContent: (@Composable () -> Unit)? = if (leadingIcon != null || prefix != null) {
            {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    leadingIcon?.invoke()
                    prefix?.invoke()
                }
            }
        } else null
        Column {
            MiuixTextField(value = value, onValueChange = onValueChange, modifier = editingModifier,
                backgroundColor = native.secondaryContainer.controlContainer(),
                label = labelText, labelColor = if (isError) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = enabled, readOnly = readOnly, textStyle = textStyle.copy(color = contentColor),
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                leadingIcon = leadingContent, trailingIcon = trailingIcon,
                singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                visualTransformation = visualTransformation, interactionSource = editingInteraction)
            if (supportingText != null) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
                CompositionLocalProvider(LocalContentColor provides
                    if (isError) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalTextStyle provides MaterialTheme.typography.bodySmall) { supportingText() }
            }
        }
    }
}

@Composable
internal fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars,
    content: @Composable (PaddingValues) -> Unit
) {
    if (LocalMiuixStyle.current) MiuixScaffold(modifier = modifier, topBar = topBar, bottomBar = bottomBar,
        snackbarHost = snackbarHost, containerColor = containerColor,
        contentWindowInsets = contentWindowInsets, content = content)
    else androidx.compose.material3.Scaffold(modifier = modifier, topBar = topBar, bottomBar = bottomBar,
        snackbarHost = snackbarHost, containerColor = containerColor,
        contentWindowInsets = contentWindowInsets, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopAppBar(
    title: @Composable () -> Unit,
    titleText: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background
) {
    val header = if (containerColor == Color.Transparent) {
        modifier.wallpaperGlyphOutline((if (LocalLaundryDarkTheme.current) Color.Black else Color.White).copy(alpha = .8f))
    } else modifier
    if (LocalMiuixStyle.current) MiuixTopAppBar(titleText, header, color = containerColor,
        navigationIcon = navigationIcon, actions = actions)
    else androidx.compose.material3.TopAppBar(title, header, navigationIcon, actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor, scrolledContainerColor = containerColor))
}

