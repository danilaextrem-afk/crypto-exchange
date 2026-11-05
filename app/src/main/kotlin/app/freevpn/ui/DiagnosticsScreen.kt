package app.freevpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.freevpn.FreeVpnApp
import app.freevpn.vpn.DiagLine
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(app: FreeVpnApp) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<DiagLine>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val log by app.vpn.log.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Диагностика", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Тест скорости использует публичный сервис speed.cloudflare.com, внешний IP — api.ipify.org. " +
                "Тест расходует несколько мегабайт трафика.",
            style = MaterialTheme.typography.bodyMedium, color = TextLo
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !running,
            onClick = {
                running = true
                scope.launch { lines = app.vpn.runDiagnostics(); running = false }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
        ) { Text(if (running) "Выполняется…" else "Запустить проверку") }
        Spacer(Modifier.height(12.dp))

        lines.forEach { l ->
            Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column {
                    Row {
                        Text(
                            when (l.level) { 0 -> "●"; 1 -> "●"; 2 -> "●"; else -> "○" },
                            color = when (l.level) { 0 -> Accent; 1 -> Warn; 2 -> Bad; else -> TextLo }
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(l.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(l.value, style = MaterialTheme.typography.bodyMedium, color = TextLo)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Журнал событий", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Panel(Modifier.fillMaxWidth()) {
            Text(
                if (log.isEmpty()) "Пока пусто" else log.takeLast(60).joinToString("\n"),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextLo
            )
        }
    }
}
