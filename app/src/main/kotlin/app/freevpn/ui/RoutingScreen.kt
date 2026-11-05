package app.freevpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.freevpn.FreeVpnApp
import app.freevpn.core.RoutingMode
import app.freevpn.core.RuApps
import app.freevpn.vpn.AppEntry
import app.freevpn.vpn.InstalledApps
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RoutingScreen(app: FreeVpnApp) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val state by app.vpn.state.collectAsState()
    var mode by remember { mutableStateOf(app.prefs.mode) }
    var vpnApps by remember { mutableStateOf(app.prefs.vpnApps) }
    var directApps by remember { mutableStateOf(app.prefs.directApps) }
    var query by remember { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }

    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.Default) { InstalledApps.list(pm).filter { it.packageName != ctx.packageName } }
    }
    val visible = apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    val installedRu = apps.filter { it.packageName in RuApps.packages }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Маршруты", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        RoutingMode.values().forEach { m ->
            val on = m == mode
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .border(1.5.dp, if (on) Accent else Surface2, RoundedCornerShape(16.dp))
                    .clickable { mode = m; app.prefs.mode = m; changed = true }
                    .padding(14.dp)
            ) {
                Column {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, color = if (on) Accent else TextHi)
                    Text(m.description, style = MaterialTheme.typography.bodyMedium, color = TextLo)
                }
            }
        }

        if (changed && (state is VpnState.Connected || state is VpnState.Reconnecting)) {
            Button(
                onClick = { app.vpn.connect(); changed = false },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
            ) { Text("Переподключить с новыми настройками") }
        } else if (changed) {
            Text("Применится при следующем подключении", color = TextLo, style = MaterialTheme.typography.bodyMedium)
        }

        when (mode) {
            RoutingMode.ALL -> {
                Spacer(Modifier.height(12.dp))
                Text("Исключений нет: весь трафик идёт через VPN.", color = TextLo)
            }
            else -> {
                if (mode == RoutingMode.RU_DIRECT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Напрямую (установлено на этом телефоне): " +
                            (if (installedRu.isEmpty()) "ничего из списка" else installedRu.joinToString { it.label }) +
                            ". Совместимость с конкретными приложениями не проверена — если приложение ведёт себя " +
                            "странно, переключите его вручную ниже.",
                        style = MaterialTheme.typography.bodyMedium, color = TextLo
                    )
                    Text(
                        "Разделение работает по приложениям, а не по сайтам: сайты в браузере идут через VPN.",
                        style = MaterialTheme.typography.bodyMedium, color = TextLo
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Поиск приложения") }, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (mode == RoutingMode.MANUAL) "Переключатель включён — приложение идёт через VPN"
                    else "Переключатель включён — приложение идёт напрямую",
                    style = MaterialTheme.typography.labelMedium, color = TextLo,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(visible, key = { it.packageName }) { a ->
                        val locked = mode == RoutingMode.RU_DIRECT && a.packageName in RuApps.packages
                        val checked = when (mode) {
                            RoutingMode.MANUAL -> a.packageName in vpnApps
                            else -> locked || a.packageName in directApps
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.packageName)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(a.label, maxLines = 1)
                                Text(if (locked) "из списка российских" else a.packageName, style = MaterialTheme.typography.labelMedium, color = TextLo, maxLines = 1)
                            }
                            Switch(
                                checked = checked, enabled = !locked,
                                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Bg),
                                onCheckedChange = { on ->
                                    if (mode == RoutingMode.MANUAL) {
                                        vpnApps = if (on) vpnApps + a.packageName else vpnApps - a.packageName
                                        app.prefs.vpnApps = vpnApps
                                    } else {
                                        directApps = if (on) directApps + a.packageName else directApps - a.packageName
                                        app.prefs.directApps = directApps
                                    }
                                    changed = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val pm = LocalContext.current.packageManager
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.Default) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
}
