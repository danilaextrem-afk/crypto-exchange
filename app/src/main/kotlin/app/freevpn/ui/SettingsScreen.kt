package app.freevpn.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.freevpn.FreeVpnApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: FreeVpnApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf(app.prefs.subscriptionUrls) }
    var autoSelect by remember { mutableStateOf(app.prefs.autoSelect) }
    var autoConnect by remember { mutableStateOf(app.prefs.autoConnectOnLaunch) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("Подписка на список серверов", style = MaterialTheme.typography.titleMedium)
        Text(
            "Адрес JSON-файла со списком серверов (только https). Можно указать несколько адресов — " +
                "по одному в строке; следующие используются как резервные. Список обновляется без переустановки APK.",
            style = MaterialTheme.typography.bodyMedium, color = TextLo
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = urls, onValueChange = { urls = it }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://example.com/subscription.json") })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                onClick = {
                    app.prefs.subscriptionUrls = urls
                    busy = true
                    scope.launch {
                        val r = app.repo.refreshSubscription()
                        busy = false
                        Toast.makeText(ctx, r.fold({ "Загружено серверов: $it" }, { "Ошибка: ${it.message}" }), Toast.LENGTH_LONG).show()
                    }
                }
            ) { Text(if (busy) "…" else "Сохранить и обновить") }
        }

        Spacer(Modifier.height(20.dp))
        Toggle("Автовыбор сервера", "Выбирать лучший сервер по измерениям", autoSelect) {
            autoSelect = it; app.prefs.autoSelect = it
        }
        Toggle("Подключаться при запуске", "Если разрешение VPN уже выдано", autoConnect) {
            autoConnect = it; app.prefs.autoConnectOnLaunch = it
        }

        Spacer(Modifier.height(12.dp))
        Text("Блокировка трафика при обрыве (kill switch)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Это функция Android, приложение само её включить не может. Откройте настройки VPN, выберите FreeVPN, " +
                "включите «Постоянный VPN» и «Блокировать соединения без VPN».",
            style = MaterialTheme.typography.bodyMedium, color = TextLo
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Открыть настройки VPN Android") }

        Spacer(Modifier.height(24.dp))
        Text("О приложении", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Panel(Modifier.fillMaxWidth()) {
            Text(
                "FreeVPN 0.1.0\n\n" +
                    "• Протокол: WireGuard (официальная библиотека wireguard-android).\n" +
                    "• Приложение не содержит собственных серверов: вы подключаете свой или разрешённый вам сервер.\n" +
                    "• Телеметрии и аналитики нет. Содержимое трафика не сохраняется и не анализируется.\n" +
                    "• Приватные ключи хранятся в Android Keystore (EncryptedSharedPreferences).\n" +
                    "• Сетевые обращения: ваши серверы, адрес подписки, а также зонды 1.1.1.1:443 и 8.8.8.8:443 " +
                    "(проверка туннеля) и по кнопке диагностики — speed.cloudflare.com и api.ipify.org.\n" +
                    "• Работа у конкретных провайдеров и совместимость с конкретными приложениями не гарантируются.",
                style = MaterialTheme.typography.bodyMedium, color = TextLo
            )
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextLo)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Bg))
    }
}
