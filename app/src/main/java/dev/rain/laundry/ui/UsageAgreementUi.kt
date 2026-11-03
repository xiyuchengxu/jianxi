package dev.rain.laundry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
    val valid = AgreementPolicy.validConfirmation(confirmation)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    UsageDialogFrame("免责声明与用户守则", dismissible = false, onDismiss = {}) {
        Text("首次使用，请完整阅读以下内容，并输入承诺后继续。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            documentItems("disclaimer", "免责声明", disclaimer)
            documentItems("rules", "用户守则（用户协议）", rules)
            item(key = "confirmation") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    Text("请输入以下完整承诺语句", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(AgreementPolicy.CONFIRMATION, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    OutlinedTextField(value = confirmation, onValueChange = { confirmation = it.take(128); saveError = false },
                        label = { Text("承诺确认") }, enabled = !saving, minLines = 2, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "输入承诺语句" },
                        supportingText = { Text("需与上方文字完全一致，包含中文逗号，不含引号。") },
                        isError = confirmation.length >= AgreementPolicy.CONFIRMATION.length && !valid)
                    if (saveError) Text("确认记录保存失败，请检查可用空间后重试。", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onExit, enabled = !saving, modifier = Modifier.weight(1f)) { Text("不同意并退出") }
            Button(onClick = {
                if (!valid || saving) return@Button
                saving = true
                val entered = confirmation
                scope.launch {
                    val accepted = withContext(Dispatchers.IO) { runCatching { store.accept(entered) }.getOrDefault(false) }
                    saving = false
                    if (accepted) onAccepted() else saveError = true
                }
            }, enabled = valid && !saving, modifier = Modifier.weight(1f)) {
                Text(if (saving) "正在保存…" else "确认并进入")
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
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
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
