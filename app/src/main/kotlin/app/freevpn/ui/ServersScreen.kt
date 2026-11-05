package app.freevpn.ui

import android.net.Uri
import android.provider.OpenableColumns
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
import app.freevpn.core.Provider
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

    // Можно выбрать сразу несколько .conf (например, конфиги Proton для разных стран).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        val errors = ArrayList<String>()
        for (uri in uris) {
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            try {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readUpTo(512 * 1024).toString(Charsets.UTF_8) }
                added += app.repo.importText(text, fileName = name)
            } catch (e: Exception) {
                errors += "${name ?: "файл"}: ${e.message}"
            }
        }
        val msg = buildString {
            append("Добавлено серверов: $added")
            if (errors.isNotEmpty()) append("\nОшибки:\n").append(errors.joinToString("\n"))
        }
        Toast.makeText(ctx, msg, if (errors.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
    var showGuide by remember { mutableStateOf(false) }

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
        TextButton(onClick = { showGuide = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Бесплатные серверы: как получить", color = Accent)
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
                    "Серверов пока нет. Встроенных серверов в приложении нет — подключение идёт только к тем, " +
                        "что вы добавите. Бесплатный вариант — Proton VPN Free: нажмите «Бесплатные серверы: как получить». " +
                        "Также подходит конфиг WireGuard (.conf) своего сервера или адрес подписки в «Ещё».",
                    color = TextLo
                )
            }
        }

        // Честно показываем ограничения бесплатных тарифов добавленных провайдеров.
        servers.mapNotNull { Provider.byId(it.provider) }.distinct().mapNotNull { p -> p.limits?.let { p.title to it } }.forEach { (title, limits) ->
            Panel(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(limits, style = MaterialTheme.typography.bodyMedium, color = TextLo)
            }
            Spacer(Modifier.height(8.dp))
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
                                Provider.byId(s.provider)?.let { add(it.title) }
                                add(st?.latencyMs?.let { "${it.toInt()} мс" } ?: "не измерен")
                                st?.downMbps?.let { add("↓ ${"%.0f".format(it)} Мбит/с") }
                                if ((st?.lossPercent ?: 0.0) > 0.5) add("потери ${"%.0f".format(st!!.lossPercent)}%")
                                s.load?.let { add("загрузка $it%") }
                                if ((st?.consecutiveFailures ?: 0) > 0) add("сбоев подряд: ${st!!.consecutiveFailures}")
                            }
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = TextLo)
                        }
                        TextButton(onClick = { confirm = true }) { Text("Удалить", color = Bad) }
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

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("Бесплатные серверы Proton VPN") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "Proton VPN разрешает подключаться к своим серверам любым WireGuard-клиентом, " +
                                "в том числе на бесплатном тарифе. Нужен ваш бесплатный аккаунт Proton (без карты).\n\n" +
                                "1. В браузере телефона откройте account.protonvpn.com и создайте бесплатный аккаунт (тариф Free).\n" +
                                "2. Откройте раздел Downloads → WireGuard configuration.\n" +
                                "3. Задайте имя устройства, платформа — Android.\n" +
                                "4. В списке серверов выберите бесплатный (с пометкой FREE) и нажмите Create, затем Download.\n" +
                                "5. Повторите для 2–3 серверов разных стран — это резервные серверы для автопереключения.\n" +
                                "6. Здесь нажмите «Файл» и выберите все скачанные .conf.\n\n" +
                                "Ограничения бесплатного тарифа: одно подключение на аккаунт одновременно " +
                                "(не включайте Proton на другом устройстве), около 8 стран, скорость не гарантируется. " +
                                "Список серверов сам не обновляется: Proton не даёт открытого API для этого, " +
                                "новые серверы — новыми конфигами из вашего аккаунта. " +
                                "В России WireGuard может блокироваться оператором — тогда соединение не установится, " +
                                "и приложение честно покажет ошибку. Условия задаёт Proton и может их изменить.",
                            color = TextLo
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("Понятно") } }
        )
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
