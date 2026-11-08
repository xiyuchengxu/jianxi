package dev.rain.laundry.experiment

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rain.laundry.ui.LaundryTheme

/** Does not construct LaundryViewModel, SessionVault, LaundryApi or any network/payment client. */
class DrumCleanExperimentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LaundryTheme { Surface { DrumCleanExperimentScreen() } } }
    }
}

@Composable
private fun DrumCleanExperimentScreen() {
    var selected by remember { mutableIntStateOf(0) }
    var forceEntry by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showRoute by remember { mutableStateOf(false) }
    val fixture = DrumCleanFixtures.cases[selected]
    val result = DrumCleanFixtures.inspect(fixture, forceEntry)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("桶自洁 · 离线实验", style = MaterialTheme.typography.headlineSmall)
        Text("仅模拟数据 · 不能启动机器", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("此页没有联网、建单或付款操作。时间固定为 2026-11-07 12:00（上海），设备编号均为 DEMO。")
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "选择实验场景" }) { Text("场景：${fixture.label}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DrumCleanFixtures.cases.forEachIndexed { index, item ->
                    DropdownMenuItem(text = { Text(item.label) }, onClick = { selected = index; menu = false; showRoute = false })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = selected > 0, onClick = { selected--; showRoute = false },
                modifier = Modifier.semantics { contentDescription = "上一个场景" }) { Text("上一个") }
            Text("${selected + 1} / ${DrumCleanFixtures.cases.size}")
            TextButton(enabled = selected < DrumCleanFixtures.cases.lastIndex, onClick = { selected++; showRoute = false },
                modifier = Modifier.semantics { contentDescription = "下一个场景" }) { Text("下一个") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("强制展示诊断入口", Modifier.weight(1f))
            Switch(checked = forceEntry, onCheckedChange = { forceEntry = it; showRoute = false },
                modifier = Modifier.semantics { contentDescription = "强制展示诊断入口开关" })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原版列表入口：${if (result.originalListVisible) "显示" else "隐藏"}")
                Text("原版详情入口：${if (result.originalDetailsVisible) "显示" else "隐藏"}")
                Text("实验入口：${if (result.entryVisible) "显示" else "隐藏"}")
                Text(result.outcome.name, style = MaterialTheme.typography.labelLarge)
                Text(result.explanation)
                if (result.entryVisible) OutlinedButton(onClick = { showRoute = true },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "查看本地分流" }) {
                    Text("桶自洁 · 查看本地分流")
                }
            }
        }
        if (showRoute) AlertDialog(onDismissRequest = { showRoute = false },
            title = { Text("本地检查结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("机器：${result.machine}")
                    Text("模拟金额：${result.amount?.let { "¥$it" } ?: "未确认"}")
                    Text("${result.outcome.name} · ${result.explanation}")
                    Text("实验在此停止；未创建订单，未付款，未启动机器。")
                }
            },
            confirmButton = { TextButton(onClick = { showRoute = false }) { Text("关闭") } })
        Text("显示按钮只影响可见性。取得真实 SKU、服务端资格与实机启动，仍未验证。", style = MaterialTheme.typography.bodySmall)
    }
}
