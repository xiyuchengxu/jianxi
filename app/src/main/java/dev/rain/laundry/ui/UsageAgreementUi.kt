package dev.rain.laundry.ui

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import dev.rain.laundry.i18n.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rain.laundry.core.AgreementPolicy
import dev.rain.laundry.data.ConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FirstLaunchAgreement(store: ConsentStore, onAccepted: () -> Unit, onExit: () -> Unit) {
    val documents = store.documents
    var confirmation by rememberSaveable(documents.revision) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val disclaimer = remember(documents.revision) { paragraphs(documents.disclaimer) }
    val rules = remember(documents.revision) { paragraphs(documents.rules) }
    val localizedConfirmation = localizedMessage(AgreementPolicy.CONFIRMATION)
    val valid = confirmation == localizedConfirmation

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    UsageDialogFrame(appText(R.string.msg_517e649764b1), dismissible = false, onDismiss = {}) {
        Text(appText(R.string.msg_3d3fce810de2),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            documentItems("disclaimer", appText(R.string.msg_280af617c87f), disclaimer)
            documentItems("rules", appText(R.string.msg_15d8fa4535c2), rules)
            item(key = "confirmation") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    Text(appText(R.string.msg_bf737f02a6f3), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(localizedConfirmation, localize = false, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    AppOutlinedTextField(value = confirmation, onValueChange = { confirmation = it.take(maxOf(128, localizedConfirmation.length)); saveError = false },
                        labelText = appText(R.string.msg_698b0d6c12a4),
                        label = { Text(appText(R.string.msg_698b0d6c12a4)) }, enabled = !saving, minLines = 2, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = appText(R.string.msg_6ea618c519a2) },
                        supportingText = { Text(appText(R.string.msg_87b7632a53e5)) },
                        isError = confirmation.length >= localizedConfirmation.length && !valid)
                    if (saveError) Text(appText(R.string.msg_e2e2b296fc1f), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppOutlinedButton(onClick = onExit, enabled = !saving, modifier = Modifier.weight(1f)) { Text(appText(R.string.msg_40b0aade58d7)) }
            AppButton(onClick = {
                if (!valid || saving) return@AppButton
                saving = true
                val entered = AgreementPolicy.CONFIRMATION
                scope.launch {
                    val accepted = withContext(Dispatchers.IO) { runCatching { store.accept(entered) }.getOrDefault(false) }
                    saving = false
                    if (accepted) onAccepted() else saveError = true
                }
            }, enabled = valid && !saving, modifier = Modifier.weight(1f)) {
                Text(if (saving) appText(R.string.msg_6bdb4435095e) else appText(R.string.msg_4948596875a4))
            }
        }
    }
}

@Composable
fun UsageDocumentDialog(title: String, content: String, onDismiss: () -> Unit) {
    val text = remember(content) { paragraphs(content) }
    UsageDialogFrame(title, dismissible = true, onDismiss = onDismiss) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            documentItems("document", title, text, includeTitle = false)
        }
        Spacer(Modifier.height(12.dp))
        AppTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(appText(R.string.msg_3fd47edce45b)) }
    }
}

private fun paragraphs(text: String): List<String> = text.substringAfter('\n').trim()
    .split("\n\n").filter { it.isNotBlank() }

private fun LazyListScope.documentItems(key: String, title: String, text: List<String>, includeTitle: Boolean = true) {
    if (includeTitle) item(key = "$key-title", contentType = "heading") {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
    }
    itemsIndexed(text, key = { index, _ -> "$key-$index" }, contentType = { _, _ -> "paragraph" }) { _, paragraph ->
        SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun UsageDialogFrame(title: String, dismissible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false
    )) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.94f), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}
