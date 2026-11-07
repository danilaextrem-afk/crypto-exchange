package app.freevpn.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.freevpn.FreeVpnApp
import app.freevpn.core.readUpTo
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(app: FreeVpnApp) {
    val ctx = LocalContext.current
    val servers by app.repo.servers.collectAsState()
    val stats by app.repo.stats.collectAsState()
    val state by app.vpn.state.collectAsState()
    val bench by app.vpn.bench.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(app.prefs.selectedServerId) }
    var auto by remember { mutableStateOf(app.prefs.autoSelect) }
    var showAdd by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readUpTo(512 * 1024).toString(Charsets.UTF_8) }
                val n = app.repo.importText(text)
                Toast.makeText(ctx, "Добавлено серверов: $n", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val idle = state is VpnState.Disconnected || state is VpnState.Failed

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Серверы", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth().clickable {
            auto = !auto; app.prefs.autoSelect = auto
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = auto, onClick = { auto = true; app.prefs.autoSelect = true },
                colors = RadioButtonDefaults.colors(selectedColor = Accent))
            Column {
                Text("Автовыбор", style = MaterialTheme.typography.titleMedium)
                Text("Лучший по задержке, потерям, скорости и загрузке; при сбое — следующий",
                    style = MaterialTheme.typography.bodyMedium, color = TextLo)
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAdd = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) { Text("Добавить") }
            OutlinedButton(onClick = { pickFile.launch("*/*") }) { Text("Файл") }
            OutlinedButton(enabled = !refreshing, onClick = {
                refreshing = true
                scope.launch {
                    val r = app.repo.refreshSubscription()
                    refreshing = false
                    Toast.makeText(
                        ctx,
                        r.fold({ "Подписка обновлена: $it" }, { "Ошибка: ${it.message}" }),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }) { Text(if (refreshing) "…" else "Подписка") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = idle && !bench.running && servers.isNotEmpty(), onClick = { app.vpn.benchmark() }) {
                Text("Измерить все")
            }
            Spacer(Modifier.size(12.dp))
            Text(
                if (bench.text.isNotEmpty()) bench.text else if (!idle) "Измерение доступно при отключённом VPN" else "Реально поднимает туннель к каждому серверу",
                style = MaterialTheme.typography.bodyMedium, color = TextLo
            )
        }
        Spacer(Modifier.height(8.dp))

        if (servers.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    "Серверов пока нет. Нажмите «Добавить» и вставьте конфиг WireGuard (.conf) " +
                        "либо укажите адрес подписки в «Ещё». Инструкция по своему серверу — в README.",
                    color = TextLo
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(servers, key = { it.id }) { s ->
                val st = stats[s.id]
                var confirm by remember { mutableStateOf(false) }
                Panel(Modifier.fillMaxWidth().clickable {
                    selected = s.id; app.prefs.selectedServerId = s.id; auto = false; app.prefs.autoSelect = false
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !auto && selected == s.id,
                            onClick = { selected = s.id; app.prefs.selectedServerId = s.id; auto = false; app.prefs.autoSelect = false },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Text(flag(s.country), fontSize = 26.sp)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            val parts = buildList {
                                add(st?.latencyMs?.let { "${it.toInt()} мс" } ?: "не измерен")
                                st?.downMbps?.let { add("↓ ${"%.0f".format(it)} Мбит/с") }
                                if ((st?.lossPercent ?: 0.0) > 0.5) add("потери ${"%.0f".format(st!!.lossPercent)}%")
                                s.load?.let { add("загрузка $it%") }
                                if ((st?.consecutiveFailures ?: 0) > 0) add("сбоев подряд: ${st!!.consecutiveFailures}")
                            }
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = TextLo)
                        }
                        // Узлы каталога управляются каталогом: после обновления они бы вернулись.
                        if (s.source != "dir") TextButton(onClick = { confirm = true }) { Text("Удалить", color = Bad) }
                    }
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Удалить сервер?") },
                        text = { Text(s.name) },
                        confirmButton = { TextButton(onClick = { app.repo.remove(s.id); confirm = false }) { Text("Удалить", color = Bad) } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
                    )
                }
            }
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Добавить сервер") },
            text = {
                Column {
                    Text("Вставьте конфиг WireGuard или JSON-подписку", color = TextLo)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val n = app.repo.importText(text)
                        Toast.makeText(ctx, "Добавлено серверов: $n", Toast.LENGTH_SHORT).show()
                        showAdd = false
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
}
