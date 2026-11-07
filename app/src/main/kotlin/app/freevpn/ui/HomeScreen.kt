package app.freevpn.ui

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.freevpn.FreeVpnApp
import app.freevpn.core.Countries
import app.freevpn.core.Probe
import app.freevpn.core.ServerProfile
import app.freevpn.core.ServerScorer
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val stats by app.repo.stats.collectAsState()
    val probes by app.repo.probes.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var auto by remember { mutableStateOf(app.prefs.autoSelect) }
    var selectedId by remember { mutableStateOf(app.prefs.selectedServerId) }
    var probing by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vpn.connect()
        else Toast.makeText(ctx, "Без разрешения Android VPN не запустится", Toast.LENGTH_LONG).show()
    }

    val busy = state is VpnState.Connecting || state is VpnState.Reconnecting
    val connected = state as? VpnState.Connected
    val idle = state is VpnState.Disconnected || state is VpnState.Failed

    fun toggle() {
        when (state) {
            is VpnState.Connected, is VpnState.Connecting, is VpnState.Reconnecting -> vpn.disconnect()
            else -> {
                val intent = VpnService.prepare(ctx)
                if (intent != null) permission.launch(intent) else vpn.connect()
            }
        }
    }

    /** Выбор сервера; если VPN уже работает — сразу переключаемся. */
    fun choose(id: String?) {
        if (id == null) {
            auto = true; app.prefs.autoSelect = true
        } else {
            auto = false; app.prefs.autoSelect = false
            selectedId = id; app.prefs.selectedServerId = id
        }
        if (connected != null || busy) vpn.connect()
    }

    // Пока VPN выключен, раз в минуту проверяем доступность и задержку всех узлов.
    LaunchedEffect(idle) {
        if (!idle) return@LaunchedEffect
        while (true) {
            probing = true
            runCatching { app.repo.refreshProbes() }
            // Конфигурации лучших узлов получаем заранее, пока VPN выключен.
            runCatching { app.repo.prewarm(selectedId = if (auto) null else selectedId) }
            probing = false
            delay(60_000)
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }

    // Кого выберет автовыбор сейчас: лучший по измерениям среди не отмеченных как недоступные.
    val bestNow: ServerProfile? = remember(servers, stats, probes) {
        val down = probes.filterValues { !it.up }.keys
        ServerScorer.order(servers, stats, System.currentTimeMillis(), null, down).firstOrNull { it.id !in down }
    }

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

    val statusText = when (state) {
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
        is VpnState.Connecting -> "Проверяю сервер и туннель"
        VpnState.Disconnected -> if (servers.isEmpty()) "Список серверов пуст" else "Нажмите кнопку, чтобы подключиться"
    }
    val buttonLabel = when {
        connected != null -> "Отключить"
        busy -> "Отмена"
        else -> "Подключить"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FreeVPN", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(20.dp))

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
                val ink = if (connected != null || busy) Bg else TextHi
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(52.dp)) {
                        val w = 6.dp.toPx()
                        drawArc(
                            ink, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                            topLeft = Offset(w, w + 5.dp.toPx()), size = Size(size.width - 2 * w, size.width - 2 * w),
                            style = Stroke(w, cap = StrokeCap.Round)
                        )
                        drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.45f), w, StrokeCap.Round)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(buttonLabel, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(statusText, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(statusSub, style = MaterialTheme.typography.bodyMedium, color = TextLo, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))

        // Текущая страна подключения (или куда подключимся).
        Panel(Modifier.fillMaxWidth()) {
            val shown: ServerProfile? = connected?.server
                ?: if (auto) bestNow else servers.firstOrNull { it.id == selectedId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flag(shown?.country ?: "??"), fontSize = 30.sp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            shown == null -> "Страна не выбрана"
                            connected != null -> "Вы в стране: ${Countries.name(shown.country)}"
                            else -> Countries.name(shown.country)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        when {
                            shown == null -> "Добавьте серверы или дождитесь проверки"
                            connected != null -> "Сервер ${shown.name}"
                            auto -> "Автовыбор: лучший сейчас — ${shown.name}"
                            else -> "Сервер ${shown.name}"
                        },
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

        // ---------- список серверов ----------
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(
                enabled = idle && !probing,
                onClick = {
                    scope.launch {
                        probing = true
                        runCatching { app.repo.refreshDirectory() }
                        runCatching { app.repo.refreshProbes() }
                        runCatching { app.repo.prewarm(selectedId = if (auto) null else selectedId) }
                        probing = false
                    }
                }
            ) { Text(if (probing) "Проверка…" else "Обновить") }
        }
        Spacer(Modifier.height(8.dp))

        if (servers.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    "В этой сборке нет ни одного сервера. Узлы добавляются в каталог администратором " +
                        "(README, раздел «Серверы»). Свой конфиг WireGuard можно импортировать на вкладке «Серверы».",
                    color = TextLo, style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            ServerRow(
                flagText = "⚡",
                title = "Авто — самый быстрый",
                subtitle = bestNow?.let { "Сейчас лучший: ${Countries.name(it.country)}, ${it.name}" } ?: "Нет доступных серверов",
                right = bestNow?.let { pingText(it, probes[it.id], stats[it.id]?.latencyMs) } ?: "—",
                status = null,
                selected = auto,
                onClick = { choose(null) }
            )
            servers.sortedBy { it.country }.forEach { s ->
                val p = probes[s.id]
                val liveHere = connected?.server?.id == s.id
                ServerRow(
                    flagText = flag(s.country),
                    title = Countries.name(s.country),
                    subtitle = s.name,
                    right = if (liveHere && live.pingMs != null) "${live.pingMs} мс" else pingText(s, p, stats[s.id]?.latencyMs),
                    status = statusOf(s, p, probing, liveHere),
                    selected = !auto && selectedId == s.id,
                    onClick = { choose(s.id) }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Задержки измеряются до подключения: это время связи с сервером. " +
                    "Во время работы VPN показывается живой ping туннеля.",
                style = MaterialTheme.typography.labelMedium, color = TextLo
            )
        }
    }
}

private fun pingText(s: ServerProfile, p: Probe?, lastLatency: Double?): String = when {
    p != null && p.up && p.rttMs != null -> "${p.rttMs} мс"
    p != null && !p.up -> "—"
    lastLatency != null -> "${lastLatency.toInt()} мс"
    else -> "—"
}

private data class StatusInfo(val text: String, val color: androidx.compose.ui.graphics.Color)

private fun statusOf(s: ServerProfile, p: Probe?, probing: Boolean, connectedHere: Boolean): StatusInfo = when {
    connectedHere -> StatusInfo("Подключён", Accent)
    !s.isNode -> StatusInfo("Свой сервер", TextLo)
    p == null -> StatusInfo(if (probing) "Проверка…" else "Не проверен", TextLo)
    p.up -> StatusInfo("Доступен" + (p.load?.let { " · нагрузка $it%" } ?: ""), Accent)
    else -> StatusInfo("Недоступен", Bad)
}

@Composable
private fun ServerRow(
    flagText: String,
    title: String,
    subtitle: String,
    right: String,
    status: StatusInfo?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .border(1.5.dp, if (selected) Accent else Surface2, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(flagText, fontSize = 28.sp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) Accent else TextHi, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextLo, maxLines = 1)
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(status.color))
                    Spacer(Modifier.size(6.dp))
                    Text(status.text, style = MaterialTheme.typography.labelMedium, color = status.color, maxLines = 1)
                }
            }
        }
        Text(right, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
