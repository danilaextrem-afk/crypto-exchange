package app.freevpn.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.freevpn.FreeVpnApp
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.delay
import java.util.Locale

fun flag(cc: String): String {
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
    return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
}

fun fmtSpeed(bytesPerSec: Long): String {
    val mbit = bytesPerSec * 8 / 1_000_000.0
    return if (mbit >= 1) String.format(Locale.US, "%.1f Мбит/с", mbit)
    else String.format(Locale.US, "%.0f Кбит/с", bytesPerSec * 8 / 1000.0)
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
fun HomeScreen(app: FreeVpnApp) {
    val vpn = app.vpn
    val state by vpn.state.collectAsState()
    val live by vpn.live.collectAsState()
    val servers by app.repo.servers.collectAsState()
    val ctx = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vpn.connect(app.prefs.selectedServerId)
    }

    fun toggle() {
        when (state) {
            is VpnState.Connected, is VpnState.Connecting, is VpnState.Reconnecting -> vpn.disconnect()
            else -> {
                val intent = VpnService.prepare(ctx)
                if (intent != null) permission.launch(intent) else vpn.connect(app.prefs.selectedServerId)
            }
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }

    val busy = state is VpnState.Connecting || state is VpnState.Reconnecting
    val connected = state as? VpnState.Connected
    val target = when {
        connected != null -> Accent
        busy -> Warn
        state is VpnState.Failed -> Bad
        else -> Surface2
    }
    val color by animateColorAsState(target, tween(500), label = "btn")

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0.92f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring"
    )

    val statusText = when (val s = state) {
        is VpnState.Connected -> "Подключено"
        is VpnState.Connecting -> "Подключение…"
        is VpnState.Reconnecting -> "Переподключение…"
        is VpnState.Failed -> "Ошибка"
        VpnState.Disconnected -> "Не подключено"
    }
    val statusSub = when (val s = state) {
        is VpnState.Reconnecting -> s.reason
        is VpnState.Failed -> s.message
        is VpnState.Connected -> "Туннель проверен: трафик проходит"
        is VpnState.Connecting -> "Проверяю серверы и туннель"
        VpnState.Disconnected -> if (servers.isEmpty()) "Сначала добавьте сервер на вкладке «Серверы»" else "Нажмите кнопку, чтобы подключиться"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FreeVPN", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(28.dp))

        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            if (busy || connected != null) {
                Box(
                    Modifier.size(220.dp).scale(if (busy) ring else 1f).alpha(0.18f)
                        .clip(CircleShape).background(color)
                )
            }
            Box(
                Modifier.size(180.dp).clip(CircleShape).background(color).clickable { toggle() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(64.dp)) {
                    val w = 7.dp.toPx()
                    val ink = if (connected != null || busy) Bg else TextHi
                    drawArc(
                        ink, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(w, w + 6.dp.toPx()), size = Size(size.width - 2 * w, size.width - 2 * w),
                        style = Stroke(w, cap = StrokeCap.Round)
                    )
                    drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.45f), w, StrokeCap.Round)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(statusText, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(statusSub, style = MaterialTheme.typography.bodyMedium, color = TextLo, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        Panel(Modifier.fillMaxWidth()) {
            val srv = connected?.server
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flag(srv?.country ?: "??"), fontSize = 30.sp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(srv?.name ?: if (app.prefs.autoSelect) "Автовыбор сервера" else "Сервер не подключён", style = MaterialTheme.typography.titleMedium)
                    Text(
                        srv?.let { "Страна: ${it.country}" } ?: "Лучший сервер выбирается по задержке, потерям и загрузке",
                        style = MaterialTheme.typography.bodyMedium, color = TextLo
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Ping", live.pingMs?.let { "$it мс" } ?: "—", Modifier.weight(1f))
            Stat("Потери", if (connected != null) "${live.lossPercent} %" else "—", Modifier.weight(1f))
            Stat("Время", connected?.let { fmtDuration(now - it.sinceMs) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Загрузка ↓", if (connected != null) fmtSpeed(live.rxBps) else "—", Modifier.weight(1f))
            Stat("Отдача ↑", if (connected != null) fmtSpeed(live.txBps) else "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Panel(modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextLo)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
