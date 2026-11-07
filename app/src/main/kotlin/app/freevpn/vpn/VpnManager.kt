package app.freevpn.vpn

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import app.freevpn.core.Backoff
import app.freevpn.core.HealthTracker
import app.freevpn.core.RoutingMode
import app.freevpn.core.RoutingPlanner
import app.freevpn.core.ServerProfile
import app.freevpn.core.ServerScorer
import app.freevpn.core.SpeedMeter
import app.freevpn.core.SplitPlan
import app.freevpn.core.Verdict
import app.freevpn.core.readUpTo
import app.freevpn.data.Prefs
import app.freevpn.data.ServerRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data class Connected(val server: ServerProfile, val sinceMs: Long) : VpnState
    data class Reconnecting(val reason: String) : VpnState
    data class Failed(val message: String) : VpnState
}

data class LiveStats(
    val rxBps: Long = 0,
    val txBps: Long = 0,
    val pingMs: Long? = null,
    val lossPercent: Int = 0,
    val totalRx: Long = 0,
    val totalTx: Long = 0
)

data class DiagLine(val title: String, val value: String, val level: Int) // 0 ок, 1 внимание, 2 ошибка, 3 инфо

data class BenchState(val running: Boolean = false, val text: String = "")

/**
 * Единственный владелец туннеля. Живёт на уровне Application, поэтому мониторинг и
 * переподключение продолжаются при выключенном экране и закрытом интерфейсе
 * (пока система не убила процесс).
 */
class VpnManager(
    private val app: Application,
    private val prefs: Prefs,
    private val repo: ServerRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = GoBackend(app)

    @Volatile private var tunnelUp = false
    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            tunnelUp = newState == Tunnel.State.UP
        }
    }

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveStats())
    val live: StateFlow<LiveStats> = _live.asStateFlow()

    private val _bench = MutableStateFlow(BenchState())
    val bench: StateFlow<BenchState> = _bench.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var session: Job? = null
    private var benchJob: Job? = null

    // ---------- сеть ----------

    private val networkEpoch = AtomicInteger(0)
    private val networks = ConcurrentHashMap.newKeySet<Network>()
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    init {
        // Следим за физическими сетями (Wi-Fi / мобильная). Любое изменение набора сетей
        // приводит к быстрому пересозданию туннеля, чтобы сокеты привязались к новой сети.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networks.add(network)) networkEpoch.incrementAndGet()
            }

            override fun onLost(network: Network) {
                if (networks.remove(network)) networkEpoch.incrementAndGet()
            }
        })
    }

    // ---------- публичный API ----------

    fun installedLauncherPackages(): Set<String> = InstalledApps.launcherPackages(app.packageManager)

    fun currentPlan(): SplitPlan = RoutingPlanner.plan(
        prefs.mode, installedLauncherPackages(), prefs.vpnApps, prefs.directApps, app.packageName
    )

    /** В режиме автовыбора предпочтений нет: лучший сервер определяют измерения. */
    private fun defaultPreferred(): String? = if (prefs.autoSelect) null else prefs.selectedServerId

    fun connect(preferredId: String? = defaultPreferred()) {
        benchJob?.cancel()
        val prev = session
        session = scope.launch {
            prev?.cancelAndJoin()
            benchJob?.cancelAndJoin()
            runSession(preferredId)
        }
    }

    fun disconnect() {
        benchJob?.cancel()
        val prev = session
        session = scope.launch {
            prev?.cancelAndJoin()
            withContext(NonCancellable) { bringDown() }
            _state.value = VpnState.Disconnected
            _live.value = LiveStats()
            log("Отключено пользователем")
        }
    }

    /** Измеряет все серверы по очереди (реальное поднятие туннеля). Только при отключённом VPN. */
    fun benchmark() {
        if (_state.value is VpnState.Connected || _state.value is VpnState.Connecting ||
            _state.value is VpnState.Reconnecting || _bench.value.running
        ) return
        benchJob = scope.launch {
            val servers = repo.servers.value
            try {
                servers.forEachIndexed { i, s ->
                    _bench.value = BenchState(true, "Проверка ${i + 1}/${servers.size}: ${s.name}")
                    if (!tryServer(s, announce = false)) {
                        log("Измерение: ${s.name} — недоступен")
                    } else {
                        val rtts = (1..3).mapNotNull { tcpRtt("1.1.1.1").also { delay(200) } }.sorted()
                        val loss = (3 - rtts.size) * 100.0 / 3
                        repo.recordLoss(s.id, loss)
                        if (rtts.isNotEmpty()) repo.recordSuccess(s.id, rtts[rtts.size / 2])
                        quickDownMbps()?.let { repo.recordSpeed(s.id, it) }
                        log("Измерение: ${s.name} — ${rtts.getOrNull(rtts.size / 2) ?: "—"} мс")
                    }
                    withContext(NonCancellable) { bringDown() }
                }
                _bench.value = BenchState(false, "Готово")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { bringDown() }
                _bench.value = BenchState(false, "")
                throw e
            }
        }
    }

    // ---------- сессия ----------

    private enum class Reason { NETWORK_CHANGED, UNHEALTHY, REVOKED }

    private suspend fun runSession(preferredId: String?) {
        _state.value = VpnState.Connecting
        _live.value = LiveStats()
        var everConnected = false
        var attempt = 0
        var prefer: String? = preferredId
        var avoid = emptySet<String>()
        log("Подключение…")

        while (currentCoroutineContext().isActive) {
            val servers = repo.servers.value
            if (servers.isEmpty()) {
                _state.value = VpnState.Failed(
                    "Список серверов пуст. Каталог узлов ещё не заполнен — см. README (раздел «Серверы»)."
                )
                return
            }
            val ordered = orderServers(servers, prefer, avoid)
            var connected: ServerProfile? = null
            for (s in ordered) {
                if (tryServer(s, announce = everConnected.not())) { connected = s; break }
            }

            if (connected == null) {
                withContext(NonCancellable) { bringDown() }
                if (!everConnected) {
                    _state.value = VpnState.Failed("Ни один сервер не ответил. Проверьте конфигурацию и интернет.")
                    log("Все серверы недоступны")
                    return
                }
                attempt++
                val wait = Backoff.seconds(attempt)
                _state.value = VpnState.Reconnecting("Нет связи с серверами, повтор через $wait с")
                delay(wait * 1000L)
                avoid = emptySet()
                continue
            }

            everConnected = true
            attempt = 0
            prefer = connected.id
            avoid = emptySet()
            when (monitor(connected)) {
                Reason.REVOKED -> {
                    _state.value = VpnState.Failed("VPN остановлен системой или другим VPN-приложением")
                    _live.value = LiveStats()
                    log("Туннель остановлен системой")
                    return
                }
                Reason.NETWORK_CHANGED -> {
                    _state.value = VpnState.Reconnecting("Сеть изменилась")
                    log("Смена сети → переподключение")
                    // Сеть другая — лучший сервер мог измениться, поэтому выбираем заново.
                    if (prefs.autoSelect) prefer = null
                }
                Reason.UNHEALTHY -> {
                    _state.value = VpnState.Reconnecting("Сервер не отвечает, переключаюсь")
                    repo.recordFailure(connected.id)
                    avoid = setOf(connected.id)
                    log("Сервер ${connected.name} не отвечает → резервный")
                }
            }
        }
    }

    private fun orderServers(servers: List<ServerProfile>, prefer: String?, avoid: Set<String>): List<ServerProfile> {
        val now = System.currentTimeMillis()
        val preferred = if (prefs.autoSelect) prefer?.takeIf { it !in avoid } else (prefs.selectedServerId ?: prefer)
        // Свежая нагрузка узла (из последней проверки) точнее, чем записанная в каталоге.
        val probes = repo.probes.value
        val withLoad = servers.map { s -> probes[s.id]?.load?.let { s.copy(load = it) } ?: s }
        // Даже при ручном выборе остальные серверы остаются резервными.
        return ServerScorer.order(withLoad, repo.stats.value, now, preferred, avoid)
    }

    /** Поднимает туннель на сервере и убеждается, что трафик реально ходит. */
    private suspend fun tryServer(s: ServerProfile, announce: Boolean): Boolean {
        if (announce) _state.value = VpnState.Connecting
        var reprovision = false
        var rtt: Long? = null
        // Для узла каталога допускается один повтор с новой регистрацией: узел мог забыть
        // устройство (долгая неактивность) или быть переустановлен.
        for (round in 0..1) {
            if (s.isNode) {
                // Регистрация идёт по обычной сети. Если туннель ещё поднят (смена сервера),
                // запрос ушёл бы в старый, возможно мёртвый туннель, поэтому гасим его заранее.
                withContext(NonCancellable) { bringDown() }
            }
            val text = try {
                repo.resolveConfig(s, force = reprovision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("${s.name}: не удалось получить конфигурацию (${e.message})")
                repo.recordFailure(s.id)
                return false
            }
            val cfg = try {
                TunnelConfigFactory.build(text, currentPlan())
            } catch (e: Exception) {
                log("Конфиг ${s.name}: ${e.message}")
                repo.recordFailure(s.id)
                return false
            }
            try {
                withContext(Dispatchers.IO) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    backend.setState(tunnel, Tunnel.State.UP, cfg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Не удалось поднять туннель ${s.name}: ${e.message}")
                repo.recordFailure(s.id)
                withContext(NonCancellable) { bringDown() }
                return false
            }
            if (waitHandshake(10_000)) break
            log("${s.name}: нет рукопожатия WireGuard")
            withContext(NonCancellable) { bringDown() }
            if (s.isNode && round == 0) {
                log("${s.name}: регистрируюсь заново")
                reprovision = true
                continue
            }
            repo.recordFailure(s.id)
            return false
        }
        for (i in 1..3) {
            rtt = probeRtt()
            if (rtt != null) break
            delay(500)
        }
        if (rtt == null) {
            log("${s.name}: туннель поднят, но интернет через него не работает")
            repo.recordFailure(s.id)
            withContext(NonCancellable) { bringDown() }
            return false
        }
        repo.recordSuccess(s.id, rtt)
        if (announce) {
            _state.value = VpnState.Connected(s, System.currentTimeMillis())
            log("Подключено: ${s.name} (${rtt} мс)")
        }
        return true
    }

    private suspend fun monitor(server: ServerProfile): Reason {
        _state.value = VpnState.Connected(server, System.currentTimeMillis())
        val epoch0 = networkEpoch.get()
        val meter = SpeedMeter(smoothing = 0.6)
        val health = HealthTracker(maxConsecutiveFailures = 3)
        val window = ArrayDeque<Boolean>()
        var tick = 0
        while (true) {
            delay(1000)
            tick++
            if (!tunnelUp) return Reason.REVOKED
            if (networkEpoch.get() != epoch0) {
                delay(1500) // даём сети устояться
                return Reason.NETWORK_CHANGED
            }
            val st = runCatching { backend.getStatistics(tunnel) }.getOrNull()
            if (st != null) {
                val (rx, tx) = meter.update(st.totalRx(), st.totalTx(), SystemClock.elapsedRealtime())
                _live.update { it.copy(rxBps = rx, txBps = tx, totalRx = st.totalRx(), totalTx = st.totalTx()) }
            }
            if (tick % 5 == 0) {
                val rtt = probeRtt()
                window.addLast(rtt != null)
                if (window.size > 20) window.removeFirst()
                val loss = window.count { !it } * 100 / window.size
                _live.update { it.copy(pingMs = rtt ?: it.pingMs, lossPercent = loss) }
                if (rtt != null) repo.recordLatency(server.id, rtt)
                if (tick % 60 == 0) repo.recordLoss(server.id, loss.toDouble())
                if (health.record(rtt != null) == Verdict.DEAD) return Reason.UNHEALTHY
            }
        }
    }

    private suspend fun waitHandshake(timeoutMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        var lastKick = 0L
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (handshakeEpoch(runCatching { backend.getStatistics(tunnel) }.getOrNull()) > 0) return true
            val now = SystemClock.elapsedRealtime()
            if (now - lastKick > 2000) {
                lastKick = now
                scope.launch { tcpRtt("1.1.1.1", timeoutMs = 1500) } // трафик инициирует рукопожатие
            }
            delay(250)
        }
        return false
    }

    private fun handshakeEpoch(st: Statistics?): Long {
        if (st == null) return 0
        return st.peers().maxOfOrNull { st.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
    }

    private suspend fun bringDown() {
        withContext(Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                log("Остановка туннеля: ${e.message}")
            }
        }
        tunnelUp = false
    }

    // ---------- зонды ----------

    private suspend fun tcpRtt(host: String, port: Int = 443, timeoutMs: Int = 2500): Long? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    val t0 = SystemClock.elapsedRealtime()
                    s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                    SystemClock.elapsedRealtime() - t0
                }
            } catch (e: IOException) {
                null
            }
        }

    /** Задержка = время TCP-подключения к публичному anycast-адресу через туннель. */
    private suspend fun probeRtt(): Long? = tcpRtt("1.1.1.1") ?: tcpRtt("8.8.8.8")

    private suspend fun quickDownMbps(bytes: Int = 2_000_000): Double? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            val t0 = SystemClock.elapsedRealtime()
            val n = c.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024); var total = 0L
                while (true) { val r = ins.read(buf); if (r < 0) break; total += r }
                total
            }
            c.disconnect()
            val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
            if (n > 0 && sec > 0) n * 8 / 1e6 / sec else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- диагностика ----------

    suspend fun runDiagnostics(): List<DiagLine> = withContext(Dispatchers.IO) {
        val out = ArrayList<DiagLine>()
        val st = _state.value
        val connected = st as? VpnState.Connected
        out += DiagLine(
            "Туннель",
            connected?.let { "Активен: ${it.server.name}" } ?: "Не подключён (проверки идут по обычной сети)",
            if (connected != null) 0 else 1
        )
        out += DiagLine("Режим маршрутизации", prefs.mode.title, 3)
        if (connected != null) {
            val hs = handshakeEpoch(runCatching { backend.getStatistics(tunnel) }.getOrNull())
            out += if (hs > 0) {
                val age = (System.currentTimeMillis() - hs) / 1000
                DiagLine("Последнее рукопожатие", "$age с назад", if (age < 180) 0 else 1)
            } else DiagLine("Последнее рукопожатие", "нет данных", 2)
        }

        val rtts = ArrayList<Long?>()
        repeat(5) { rtts += probeRtt(); delay(200) }
        val ok = rtts.filterNotNull()
        val loss = (rtts.size - ok.size) * 100 / rtts.size
        out += DiagLine(
            "Задержка (TCP до 1.1.1.1, 5 проб)",
            if (ok.isEmpty()) "нет ответа" else "мин ${ok.min()} / сред ${ok.average().toInt()} / макс ${ok.max()} мс",
            if (ok.isEmpty()) 2 else if (ok.average() < 150) 0 else 1
        )
        out += DiagLine("Потери проб", "$loss %", if (loss == 0) 0 else if (loss < 40) 1 else 2)

        val dnsStart = SystemClock.elapsedRealtime()
        val dnsOk = try { InetAddress.getByName("example.com"); true } catch (e: Exception) { false }
        out += DiagLine(
            "Разрешение DNS",
            if (dnsOk) "${SystemClock.elapsedRealtime() - dnsStart} мс" else "ошибка",
            if (dnsOk) 0 else 2
        )

        val ip = httpText("https://api.ipify.org")
        val endpoint = connected?.server?.publicHost
        out += when {
            ip == null -> DiagLine("Внешний IP", "не удалось определить", 1)
            connected == null -> DiagLine("Внешний IP", "$ip (VPN выключен)", 3)
            endpoint != null && endpoint == ip -> DiagLine("Внешний IP", "$ip — совпадает с сервером", 0)
            else -> DiagLine(
                "Внешний IP",
                "$ip — адрес сервера: ${endpoint ?: "?"}. Если адреса не совпадают, " +
                    "проверьте, что выход сервера не использует другой IP (NAT/балансировщик).",
                1
            )
        }
        out += DiagLine(
            "Защита от утечек",
            "Полный туннель IPv4+IPv6, DNS через туннель. Для блокировки трафика при обрыве " +
                "включите «Постоянный VPN» и «Блокировать соединения без VPN» в настройках Android.",
            3
        )

        val down = quickDownMbps(8_000_000)
        out += DiagLine(
            "Скорость загрузки",
            down?.let { "%.1f Мбит/с".format(Locale.US, it) } ?: "тест не удался",
            if (down == null) 2 else 0
        )
        val up = quickUpMbps()
        out += DiagLine(
            "Скорость отдачи",
            up?.let { "%.1f Мбит/с".format(Locale.US, it) } ?: "тест не удался",
            if (up == null) 2 else 0
        )
        out += DiagLine(
            "Хранилище ключей",
            if (prefs.secureStorage) "Android Keystore (AES-256)" else "Keystore недоступен — приватное хранилище приложения",
            if (prefs.secureStorage) 0 else 1
        )
        out
    }

    private fun httpText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        val body = c.inputStream.use { it.readUpTo(256) }.toString(Charsets.UTF_8).trim()
        c.disconnect()
        body.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun quickUpMbps(bytes: Int = 2_000_000): Double? = try {
        val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 5000; c.readTimeout = 15000
        c.setFixedLengthStreamingMode(bytes)
        val t0 = SystemClock.elapsedRealtime()
        c.outputStream.use { os ->
            val chunk = ByteArray(32 * 1024); var left = bytes
            while (left > 0) { val n = minOf(left, chunk.size); os.write(chunk, 0, n); left -= n }
        }
        c.responseCode
        c.disconnect()
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        if (sec > 0) bytes * 8 / 1e6 / sec else null
    } catch (e: Exception) {
        null
    }

    private fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        _log.update { (it + line).takeLast(200) }
    }

    companion object {
        const val TUNNEL_NAME = "freevpn"
    }
}
