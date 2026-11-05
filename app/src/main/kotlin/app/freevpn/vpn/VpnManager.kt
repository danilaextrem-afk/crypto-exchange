package app.freevpn.vpn

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.freevpn.core.Backoff
import app.freevpn.core.HealthTracker
import app.freevpn.core.RoutingMode
import app.freevpn.core.RoutingPlanner
import app.freevpn.core.ServerProfile
import app.freevpn.core.ServerScorer
import app.freevpn.core.SessionPolicy
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
import java.util.concurrent.atomic.AtomicReference

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
    /** Какой сервер (и план Split Tunneling) сейчас загружен в поднятый туннель. */
    @Volatile private var loaded: Pair<String, SplitPlan>? = null
    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            tunnelUp = newState == Tunnel.State.UP
            if (!tunnelUp) loaded = null
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
    /** Физическая сеть, через которую реально идёт трафик (не VPN). */
    private val primary = AtomicReference<Network?>(null)

    init {
        // Следим за физическими сетями (Wi-Fi / мобильная; VPN исключён запросом по умолчанию).
        // Туннель пересоздаётся только когда меняется ОСНОВНАЯ сеть. Раньше реагировали на любую:
        // при включённых одновременно Wi-Fi и мобильных данных появление/пропадание второй сети
        // без причины рвало соединение.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks.add(network)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) choosePrimaryByTransport()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) choosePrimaryByTransport()
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: система сама сообщает лучшую (основную) физическую сеть.
            cm.registerBestMatchingNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = setPrimary(network)
                override fun onLost(network: Network) {
                    if (primary.get() == network) setPrimary(null)
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun setPrimary(n: Network?) {
        if (primary.getAndSet(n) != n) networkEpoch.incrementAndGet()
    }

    /** Android 8–11: основная сеть — Wi-Fi, если есть, затем Ethernet, затем мобильная. */
    private fun choosePrimaryByTransport() {
        fun rank(n: Network): Int {
            val c = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return 0
            return when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 0
            }
        }
        setPrimary(networks.maxByOrNull { rank(it) })
    }

    // ---------- публичный API ----------

    fun installedLauncherPackages(): Set<String> = InstalledApps.launcherPackages(app.packageManager)

    fun currentPlan(): SplitPlan = RoutingPlanner.plan(
        prefs.mode, installedLauncherPackages(), prefs.vpnApps, prefs.directApps, app.packageName
    )

    /**
     * @param persistent true — не сдаваться, если серверы не отвечают с первой попытки
     *   (запуск системой «Постоянный VPN», например сразу после перезагрузки, когда сети ещё нет).
     */
    fun connect(preferredId: String? = prefs.selectedServerId, persistent: Boolean = false) {
        benchJob?.cancel()
        val prev = session
        session = scope.launch {
            prev?.cancelAndJoin()
            benchJob?.cancelAndJoin()
            runSession(preferredId, persistent)
        }
    }

    /**
     * Android запустил VPN-сервис сам (включён «Постоянный VPN»): после перезагрузки,
     * после остановки процесса или при включении настройки. Без этого туннель не
     * поднимался, а при «Блокировать соединения без VPN» интернет пропадал совсем.
     */
    fun onAlwaysOnStart() {
        val s = _state.value
        if (s is VpnState.Connected || s is VpnState.Connecting || s is VpnState.Reconnecting) return
        log("Запуск системой («Постоянный VPN»)")
        connect(persistent = true)
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

    private enum class Reason { NETWORK_CHANGED, UNHEALTHY, REVOKED, TUNNEL_LOST }

    private suspend fun runSession(preferredId: String?, persistent: Boolean) {
        _state.value = VpnState.Connecting
        _live.value = LiveStats()
        var everConnected = false
        var attempt = 0
        var prefer: String? = preferredId
        var avoid = emptySet<String>()
        var afterUnhealthy = false
        var netChanged = false
        log("Подключение…")

        while (currentCoroutineContext().isActive) {
            // Разрешение могло пропасть (включили другой VPN). Без него GoBackend всё равно
            // не поднимет туннель, а серверы получили бы ложные «сбои».
            if (VpnService.prepare(app) != null) {
                withContext(NonCancellable) { bringDown() }
                _state.value = VpnState.Failed(
                    if (everConnected) "VPN остановлен системой или другим VPN-приложением"
                    else "Нет разрешения на VPN: нажмите кнопку и подтвердите запрос Android"
                )
                _live.value = LiveStats()
                log("Нет разрешения на VPN")
                return
            }
            val servers = repo.servers.value
            if (servers.isEmpty()) {
                withContext(NonCancellable) { bringDown() }
                _state.value = VpnState.Failed("Нет серверов. Добавьте конфигурацию или подписку.")
                return
            }
            val hold = SessionPolicy.holdTunnelOnFailure(everConnected, persistent)
            awaitNetwork()
            var ordered = orderServers(servers, prefer, avoid)
            // Каждое пересоздание туннеля — доли секунды без VPN (так устроен GoBackend). Поэтому,
            // пока все серверы недоступны, держим уже загруженный туннель и проверяем его сервер,
            // а остальные пробуем только в каждом третьем цикле.
            val reuse = !netChanged && (afterUnhealthy || attempt > 0)
            val held = loaded?.first?.takeIf { tunnelUp }
            if (!netChanged && attempt > 0 && held != null) {
                ordered.firstOrNull { it.id == held }?.let { h ->
                    ordered = if (attempt % 3 == 0) listOf(h) + (ordered - h) else listOf(h)
                }
            }
            netChanged = false
            var connected: ServerProfile? = null
            for (s in ordered) {
                if (tryServer(s, announce = everConnected.not(), holdOnFailure = hold, reuse = reuse)) { connected = s; break }
            }

            if (connected == null) {
                if (!hold) {
                    withContext(NonCancellable) { bringDown() }
                    _state.value = VpnState.Failed("Ни один сервер не ответил. Проверьте конфигурацию и интернет.")
                    log("Все серверы недоступны")
                    return
                }
                attempt++
                val wait = Backoff.seconds(attempt)
                _state.value = VpnState.Reconnecting(
                    if (tunnelUp) "Серверы не отвечают. Трафик заблокирован до восстановления, повтор через $wait с"
                    else "Нет связи с серверами, повтор через $wait с"
                )
                log("Все серверы недоступны, повтор через $wait с" + if (tunnelUp) " (трафик заблокирован)" else "")
                netChanged = waitOrNetworkChange(wait * 1000L)
                avoid = emptySet()
                continue
            }

            everConnected = true
            attempt = 0
            afterUnhealthy = false
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
                    netChanged = true
                }
                Reason.TUNNEL_LOST -> {
                    _state.value = VpnState.Reconnecting("Туннель перезапускается")
                    log("Туннель остановлен без команды пользователя — поднимаю снова")
                    netChanged = true // пересоздать туннель, тот же сервер первым
                }
                Reason.UNHEALTHY -> {
                    _state.value = VpnState.Reconnecting("Сервер не отвечает, переключаюсь")
                    repo.recordFailure(connected.id)
                    avoid = setOf(connected.id)
                    afterUnhealthy = true
                    log("Сервер ${connected.name} не отвечает → резервный")
                }
            }
        }
    }

    private fun orderServers(servers: List<ServerProfile>, prefer: String?, avoid: Set<String>): List<ServerProfile> {
        val now = System.currentTimeMillis()
        val preferred = if (prefs.autoSelect) prefer?.takeIf { it !in avoid } else (prefs.selectedServerId ?: prefer)
        // Даже при ручном выборе остальные серверы остаются резервными.
        return ServerScorer.order(servers, repo.stats.value, now, preferred, avoid)
    }

    /**
     * Поднимает туннель на сервере и убеждается, что трафик реально ходит.
     * @param holdOnFailure при неудаче оставить туннель поднятым: трафик блокируется,
     *   а не уходит мимо VPN, пока ищется рабочий сервер.
     */
    private suspend fun tryServer(
        s: ServerProfile,
        announce: Boolean,
        holdOnFailure: Boolean = false,
        reuse: Boolean = false
    ): Boolean {
        if (announce) _state.value = VpnState.Connecting
        val plan = currentPlan()
        val cfg = try {
            TunnelConfigFactory.build(s.config, plan)
        } catch (e: Exception) {
            log("Конфиг ${s.name}: ${e.message}")
            repo.recordFailure(s.id)
            return false
        }
        val key = s.id to plan
        var handshake = false
        if (reuse && tunnelUp && loaded == key) {
            // Туннель к этому серверу уже поднят и держит трафик: не пересоздаём VPN-интерфейс.
            // WireGuard сам повторяет рукопожатие; годится и живая сессия (не старше 3 минут).
            handshake = waitHandshake(10_000, maxAgeMs = SESSION_MAX_AGE_MS)
        } else {
          for (attempt in 1..2) {
            try {
                withContext(Dispatchers.IO) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    backend.setState(tunnel, Tunnel.State.UP, cfg)
                }
                loaded = key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Не удалось поднять туннель ${s.name}: ${e.message}")
                repo.recordFailure(s.id)
                withContext(NonCancellable) { bringDown() }
                return false
            }
            handshake = waitHandshake(10_000)
            // При пересоздании туннеля GoBackend останавливает свой VPN-сервис, и его запоздалое
            // уничтожение может выключить уже новый туннель. Это не вина сервера: поднимаем ещё раз.
            if (handshake || tunnelUp || attempt == 2) break
            log("${s.name}: Android остановил VPN-сервис при пересоздании туннеля — повторяю")
          }
        }
        if (!handshake) {
            log("${s.name}: нет рукопожатия WireGuard")
            repo.recordFailure(s.id)
            if (!holdOnFailure) withContext(NonCancellable) { bringDown() }
            return false
        }
        // Android применяет маршруты VPN асинхронно. Пока VPN не стал сетью по умолчанию
        // для приложения, проверочный запрос мог бы уйти напрямую и дать ложное «Подключено».
        val vpnNet = awaitTunnelNetwork(5_000)
        if (vpnNet == null) {
            log("${s.name}: Android не направил трафик в VPN")
            repo.recordFailure(s.id)
            if (!holdOnFailure) withContext(NonCancellable) { bringDown() }
            return false
        }
        var rtt: Long? = null
        for (i in 1..3) {
            rtt = probeRtt(vpnNet)
            if (rtt != null) break
            delay(500)
        }
        if (rtt == null) {
            log("${s.name}: туннель поднят, но интернет через него не работает")
            repo.recordFailure(s.id)
            if (!holdOnFailure) withContext(NonCancellable) { bringDown() }
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
            // Разрешение у другого VPN — значит, нас действительно отключили. Если разрешение
            // всё ещё наше, туннель выключил запоздалый onDestroy сервиса GoBackend: поднимаем снова.
            if (!tunnelUp) return if (VpnService.prepare(app) != null) Reason.REVOKED else Reason.TUNNEL_LOST
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
                // Зонд привязан к VPN-сети: если её нет, это сбой, а не «успех напрямую».
                val rtt = tunnelNetwork()?.let { probeRtt(it) }
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

    /**
     * Ждёт появления физической сети (Wi-Fi или мобильной), но не дольше maxMs:
     * сразу после загрузки телефона сети ещё нет, и попытки только копили бы «сбои» серверов.
     */
    private suspend fun awaitNetwork(maxMs: Long = 20_000) {
        if (networks.isNotEmpty()) return
        val before = _state.value
        if (before !is VpnState.Connected) _state.value = VpnState.Reconnecting("Нет подключения к интернету, жду сеть…")
        log("Нет сети — жду подключения")
        val end = SystemClock.elapsedRealtime() + maxMs
        while (networks.isEmpty() && SystemClock.elapsedRealtime() < end) delay(500)
        if (networks.isNotEmpty()) delay(1000) // даём сети устояться
    }

    /** VPN-сеть, зарегистрированная Android (у приложения может быть только одна). */
    @Suppress("DEPRECATION")
    private fun tunnelNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Ждёт, пока VPN станет сетью по умолчанию для этого приложения. */
    private suspend fun awaitTunnelNetwork(timeoutMs: Long): Network? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val n = tunnelNetwork()
            if (n != null && cm.activeNetwork == n) return n
            delay(200)
        }
        return null
    }

    /** Пауза перед повтором, которая прерывается, как только меняется сеть. true — сеть сменилась. */
    private suspend fun waitOrNetworkChange(ms: Long): Boolean {
        val epoch = networkEpoch.get()
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (networkEpoch.get() != epoch) {
                log("Сеть изменилась — повторяю сразу")
                delay(1500)
                return true
            }
            delay(250)
        }
        return false
    }

    /** @param maxAgeMs для уже работавшего туннеля: рукопожатие должно быть не старше этого. */
    private suspend fun waitHandshake(timeoutMs: Long, maxAgeMs: Long? = null): Boolean {
        val start = SystemClock.elapsedRealtime()
        var lastKick = 0L
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hs = handshakeEpoch(runCatching { backend.getStatistics(tunnel) }.getOrNull())
            if (hs > 0 && (maxAgeMs == null || System.currentTimeMillis() - hs < maxAgeMs)) return true
            if (!tunnelUp) return false // туннель выключила система — ждать бессмысленно
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
        loaded = null
    }

    // ---------- зонды ----------

    private suspend fun tcpRtt(host: String, port: Int = 443, timeoutMs: Int = 2500, via: Network? = null): Long? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    via?.bindSocket(s)
                    val t0 = SystemClock.elapsedRealtime()
                    s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                    SystemClock.elapsedRealtime() - t0
                }
            } catch (e: IOException) {
                null
            }
        }

    /** Задержка = время TCP-подключения к публичному anycast-адресу через туннель. */
    private suspend fun probeRtt(via: Network? = null): Long? =
        tcpRtt("1.1.1.1", via = via) ?: tcpRtt("8.8.8.8", via = via)

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
        val vpnOff = st is VpnState.Disconnected || st is VpnState.Failed
        out += DiagLine(
            "Туннель",
            when {
                connected != null -> "Активен: ${connected.server.name}"
                // Во время переподключения туннель может быть поднят и блокировать трафик.
                !vpnOff -> "Переподключение — результаты проверок ниже могут быть неполными"
                else -> "Не подключён (проверки идут по обычной сети)"
            },
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
        val endpoint = connected?.server?.endpointHost
        out += when {
            ip == null -> DiagLine("Внешний IP", "не удалось определить", 1)
            connected == null -> DiagLine("Внешний IP", if (vpnOff) "$ip (VPN выключен)" else "$ip (идёт переподключение)", 3)
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
        /** WireGuard не использует сессию старше 180 с (REJECT_AFTER_TIME). */
        private const val SESSION_MAX_AGE_MS = 180_000L
    }
}
