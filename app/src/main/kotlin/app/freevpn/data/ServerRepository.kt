package app.freevpn.data

import app.freevpn.core.DirectoryParser
import app.freevpn.core.NodeProtocol
import app.freevpn.core.Probe
import app.freevpn.core.ServerProfile
import app.freevpn.core.ServerScorer
import app.freevpn.core.ServerStats
import app.freevpn.core.SubscriptionParser
import app.freevpn.core.readUpTo
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * @param bundledDirectory каталог узлов, вшитый в APK (assets/directory.json); null — нет.
 * @param directoryUrls публичные https-адреса каталога (основной и резервные).
 */
class ServerRepository(
    private val prefs: Prefs,
    private val bundledDirectory: String? = null,
    private val directoryUrls: List<String> = emptyList()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provisionLock = Mutex()
    private val refreshLock = Mutex()

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()

    private val _stats = MutableStateFlow(loadStats())
    val stats: StateFlow<Map<String, ServerStats>> = _stats.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, Probe>>(emptyMap())
    val probes: StateFlow<Map<String, Probe>> = _probes.asStateFlow()

    init {
        // Узлы, вшитые в APK, работают сразу после установки и без интернета для загрузки каталога.
        bundledDirectory?.let { text ->
            runCatching { DirectoryParser.parse(text) }.getOrNull()?.let { bundled ->
                val known = _servers.value.filter { it.source == "dir" }.map { it.id }.toSet()
                // Свежий каталог из сети важнее вшитого, поэтому вшитый заполняет только пустое место.
                if (known.isEmpty()) applyDirectory(bundled)
            }
        }
    }

    // ---------- серверы ----------

    /** Добавляет серверы из текста (конфиг или JSON). Возвращает число добавленных/обновлённых. */
    fun importText(text: String, source: String = "local"): Int {
        val parsed = if (DirectoryParser.isDirectory(text)) DirectoryParser.parse(text)
        else SubscriptionParser.parse(text, source)
        val map = LinkedHashMap(_servers.value.associateBy { it.id })
        parsed.forEach { map[it.id] = it }
        saveServers(map.values.toList())
        return parsed.size
    }

    fun remove(id: String) {
        saveServers(_servers.value.filterNot { it.id == id })
        forgetCredentials(id)
        if (prefs.selectedServerId == id) prefs.selectedServerId = null
    }

    /** Заменяет все узлы каталога новым списком; ручные серверы не трогает. */
    private fun applyDirectory(nodes: List<ServerProfile>) {
        val keep = _servers.value.filter { it.source != "dir" }
        val ids = nodes.map { it.id }.toSet()
        _servers.value.filter { it.source == "dir" && it.id !in ids }.forEach { forgetCredentials(it.id) }
        saveServers(keep + nodes)
        if (prefs.selectedServerId != null && _servers.value.none { it.id == prefs.selectedServerId }) {
            prefs.selectedServerId = null
        }
    }

    /** Обновляет каталог узлов по сети; пробует адреса по очереди (резервные зеркала). */
    suspend fun refreshDirectory(): Result<Int> = withContext(Dispatchers.IO) {
        if (directoryUrls.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Адрес каталога не задан при сборке"))
        }
        refreshLock.withLock {
            var last: Throwable? = null
            for (u in directoryUrls) {
                try {
                    require(u.startsWith("https://")) { "Разрешены только https-адреса" }
                    val nodes = DirectoryParser.parse(download(u))
                    applyDirectory(nodes)
                    prefs.lastDirectoryRefresh = System.currentTimeMillis()
                    return@withLock Result.success(nodes.size)
                } catch (e: Exception) {
                    last = e
                }
            }
            Result.failure(last ?: IllegalStateException("Не удалось загрузить каталог"))
        }
    }

    /** Загружает подписку пользователя (необязательна); понимает и конфиги, и формат каталога. */
    suspend fun refreshSubscription(): Result<Int> = withContext(Dispatchers.IO) {
        val urls = prefs.subscriptionUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return@withContext Result.failure(IllegalStateException("Адрес подписки не задан"))
        var last: Throwable? = null
        for (u in urls) {
            try {
                require(u.startsWith("https://")) { "Разрешены только https-адреса" }
                val text = download(u)
                val parsed = if (DirectoryParser.isDirectory(text)) DirectoryParser.parse(text)
                else SubscriptionParser.parse(text, "sub")
                val parsedIds = parsed.map { it.id }.toSet()
                val keep = _servers.value.filter { it.source != "sub" && it.id !in parsedIds }
                saveServers(keep + parsed)
                prefs.lastSubscriptionRefresh = System.currentTimeMillis()
                return@withContext Result.success(parsed.size)
            } catch (e: Exception) {
                last = e
            }
        }
        Result.failure(last ?: IllegalStateException("Не удалось загрузить подписку"))
    }

    fun refreshIfStale(maxAgeMs: Long = 30L * 60 * 1000) {
        val now = System.currentTimeMillis()
        if (directoryUrls.isNotEmpty() && now - prefs.lastDirectoryRefresh >= maxAgeMs) {
            scope.launch { refreshDirectory() }
        }
        if (prefs.subscriptionUrls.isNotBlank() && now - prefs.lastSubscriptionRefresh >= 6L * 3600 * 1000) {
            scope.launch { refreshSubscription() }
        }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "FreeVPN")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val bytes = c.inputStream.use { it.readUpTo(MAX_SUB_BYTES + 1) }
            check(bytes.size <= MAX_SUB_BYTES) { "Файл слишком большой" }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    // ---------- проверка узлов без туннеля ----------

    /**
     * Проверяет все узлы каталога параллельно: задержка до сервера и состояние по HTTPS.
     * Вызывать только при отключённом VPN — внутри туннеля задержка была бы другой.
     */
    suspend fun refreshProbes() {
        val nodes = _servers.value.filter { it.isNode }
        if (nodes.isEmpty()) return
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                nodes.map { s ->
                    async {
                        val rtt = NodeClient.tcpRtt(s.api!!)
                        if (rtt == null) {
                            s.id to Probe(false, null, null, System.currentTimeMillis())
                        } else {
                            val h = runCatching { NodeClient.health(s.api, s.pin!!) }.getOrNull()
                            s.id to Probe(h != null, rtt, h?.load, System.currentTimeMillis())
                        }
                    }
                }.awaitAll()
            }
        }
        val map = _probes.value.toMutableMap()
        results.forEach { (id, p) ->
            map[id] = p
            if (p.up) recordSuccess(id, p.rttMs) else recordFailure(id)
        }
        // Узлы, исчезшие из списка, из проб убираем.
        val alive = _servers.value.map { it.id }.toSet()
        _probes.value = map.filterKeys { it in alive }
    }

    // ---------- автоматическая выдача конфигурации ----------

    /**
     * Возвращает текст конфигурации WireGuard для сервера. Для узла каталога ключи создаются на
     * этом устройстве, на узел уходит только публичный ключ; результат запоминается.
     * force = true — зарегистрироваться заново (тем же ключом, адрес сохранится).
     * Запрос идёт по обычной сети, поэтому вызывать нужно при погашенном туннеле.
     */
    suspend fun resolveConfig(
        s: ServerProfile,
        force: Boolean = false,
        allowNetwork: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val api = s.api
        val pin = s.pin
        if (api == null || pin == null) return@withContext s.config
        provisionLock.withLock {
            val creds = JSONObject(prefs.nodeCredsJson)
            val saved = creds.optJSONObject(s.id)
            val cached = saved?.optString("config").orEmpty()
            if (!force && cached.isNotEmpty()) return@withLock cached
            check(allowNetwork) { "регистрация на узле возможна только при выключенном VPN" }

            val savedPriv = saved?.optString("priv").orEmpty()
            val pair = if (NodeProtocol.isValidKey(savedPriv)) KeyPair(Key.fromBase64(savedPriv)) else KeyPair()
            val reg = NodeClient.register(api, pin, pair.publicKey.toBase64())
            val config = NodeProtocol.buildConfig(pair.privateKey.toBase64(), reg)
            SubscriptionParser.validate(config)
            creds.put(s.id, JSONObject().put("priv", pair.privateKey.toBase64()).put("config", config))
            prefs.nodeCredsJson = creds.toString()
            config
        }
    }

    /**
     * Заранее получает конфигурации для нескольких лучших доступных узлов (и выбранного вручную),
     * пока VPN выключен. Тогда подключение и переход на резервный узел не требуют сети вне туннеля.
     */
    suspend fun prewarm(maxNodes: Int = 3, selectedId: String? = null) {
        val creds = runCatching { JSONObject(prefs.nodeCredsJson) }.getOrDefault(JSONObject())
        val probes = _probes.value
        val ranked = _servers.value.filter { it.isNode && probes[it.id]?.up == true }
            .sortedBy { probes[it.id]?.rttMs ?: Long.MAX_VALUE }
            .take(maxNodes)
        val wanted = (listOfNotNull(_servers.value.firstOrNull { it.id == selectedId && it.isNode }) + ranked).distinctBy { it.id }
        for (s in wanted) {
            if (creds.optJSONObject(s.id)?.optString("config").orEmpty().isNotEmpty()) continue
            runCatching { resolveConfig(s) }
        }
    }

    private fun forgetCredentials(id: String) {
        runCatching {
            val creds = JSONObject(prefs.nodeCredsJson)
            if (creds.has(id)) {
                creds.remove(id)
                prefs.nodeCredsJson = creds.toString()
            }
        }
    }

    // ---------- статистика качества ----------

    fun recordSuccess(id: String, rttMs: Long?) = updateStats(id) { s ->
        s.copy(
            latencyMs = if (rttMs != null) ServerScorer.ewma(s.latencyMs, rttMs.toDouble()) else s.latencyMs,
            consecutiveFailures = 0,
            successes = s.successes + 1,
            lastTestedEpoch = System.currentTimeMillis()
        )
    }

    fun recordLatency(id: String, rttMs: Long) = updateStats(id) { s ->
        s.copy(latencyMs = ServerScorer.ewma(s.latencyMs, rttMs.toDouble(), 0.1))
    }

    fun recordFailure(id: String) = updateStats(id) { s ->
        s.copy(
            consecutiveFailures = s.consecutiveFailures + 1,
            failures = s.failures + 1,
            lastTestedEpoch = System.currentTimeMillis()
        )
    }

    fun recordSpeed(id: String, downMbps: Double) = updateStats(id) { s ->
        s.copy(downMbps = ServerScorer.ewma(s.downMbps, downMbps, 0.5))
    }

    fun recordLoss(id: String, lossPercent: Double) = updateStats(id) { s ->
        s.copy(lossPercent = ServerScorer.ewma(s.lossPercent, lossPercent, 0.3))
    }

    @Synchronized
    private fun updateStats(id: String, f: (ServerStats) -> ServerStats) {
        val map = _stats.value.toMutableMap()
        map[id] = f(map[id] ?: ServerStats())
        _stats.value = map
        prefs.statsJson = statsToJson(map).toString()
    }

    // ---------- сериализация ----------

    private fun saveServers(list: List<ServerProfile>) {
        _servers.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("country", it.country)
                    .put("config", it.config).put("load", it.load ?: -1).put("source", it.source)
                    .put("api", it.api ?: JSONObject.NULL).put("pin", it.pin ?: JSONObject.NULL)
            )
        }
        prefs.serversJson = arr.toString()
    }

    private fun loadServers(): List<ServerProfile> = try {
        val arr = JSONArray(prefs.serversJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ServerProfile(
                o.getString("id"), o.getString("name"), o.getString("country"), o.getString("config"),
                o.optInt("load", -1).takeIf { l -> l >= 0 }, o.optString("source", "local"),
                if (o.isNull("api")) null else o.getString("api"),
                if (o.isNull("pin")) null else o.getString("pin")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun statsToJson(map: Map<String, ServerStats>): JSONObject {
        val o = JSONObject()
        map.forEach { (id, s) ->
            o.put(
                id, JSONObject()
                    .put("lat", s.latencyMs ?: JSONObject.NULL)
                    .put("loss", s.lossPercent)
                    .put("down", s.downMbps ?: JSONObject.NULL)
                    .put("cf", s.consecutiveFailures).put("ok", s.successes).put("fail", s.failures)
                    .put("t", s.lastTestedEpoch)
            )
        }
        return o
    }

    private fun loadStats(): Map<String, ServerStats> = try {
        val o = JSONObject(prefs.statsJson)
        o.keys().asSequence().associateWith { id ->
            val s = o.getJSONObject(id)
            ServerStats(
                latencyMs = if (s.isNull("lat")) null else s.getDouble("lat"),
                lossPercent = s.optDouble("loss", 0.0),
                downMbps = if (s.isNull("down")) null else s.getDouble("down"),
                consecutiveFailures = s.optInt("cf"), successes = s.optInt("ok"),
                failures = s.optInt("fail"), lastTestedEpoch = s.optLong("t")
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        private const val MAX_SUB_BYTES = 512 * 1024
    }
}
