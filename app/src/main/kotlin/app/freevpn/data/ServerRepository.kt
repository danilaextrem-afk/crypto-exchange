package app.freevpn.data

import app.freevpn.core.ServerProfile
import app.freevpn.core.readUpTo
import app.freevpn.core.ServerScorer
import app.freevpn.core.ServerStats
import app.freevpn.core.SubscriptionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ServerRepository(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()

    private val _stats = MutableStateFlow(loadStats())
    val stats: StateFlow<Map<String, ServerStats>> = _stats.asStateFlow()

    // ---------- серверы ----------

    /** Добавляет серверы из текста (конфиг или JSON). Возвращает число добавленных/обновлённых. */
    fun importText(text: String, source: String = "local", fileName: String? = null): Int {
        val parsed = SubscriptionParser.parse(text, source, fileName)
        val map = LinkedHashMap(_servers.value.associateBy { it.id })
        parsed.forEach { map[it.id] = it }
        saveServers(map.values.toList())
        return parsed.size
    }

    fun remove(id: String) {
        saveServers(_servers.value.filterNot { it.id == id })
        if (prefs.selectedServerId == id) prefs.selectedServerId = null
    }

    /** Загружает подписку; пробует адреса по очереди (резервные зеркала). */
    suspend fun refreshSubscription(): Result<Int> = withContext(Dispatchers.IO) {
        val urls = prefs.subscriptionUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return@withContext Result.failure(IllegalStateException("Адрес подписки не задан"))
        var last: Throwable? = null
        for (u in urls) {
            try {
                require(u.startsWith("https://")) { "Разрешены только https-адреса" }
                val parsed = SubscriptionParser.parse(download(u), "sub")
                val keepLocal = _servers.value.filter { it.source != "sub" }
                val map = LinkedHashMap<String, ServerProfile>()
                (keepLocal + parsed).forEach { map[it.id] = it }
                saveServers(map.values.toList())
                prefs.lastSubscriptionRefresh = System.currentTimeMillis()
                return@withContext Result.success(parsed.size)
            } catch (e: Exception) {
                last = e
            }
        }
        Result.failure(last ?: IllegalStateException("Не удалось загрузить подписку"))
    }

    fun refreshIfStale(maxAgeMs: Long = 6L * 3600 * 1000) {
        if (prefs.subscriptionUrls.isBlank()) return
        if (System.currentTimeMillis() - prefs.lastSubscriptionRefresh < maxAgeMs) return
        scope.launch { refreshSubscription() }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "FreeVPN")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val bytes = c.inputStream.use { it.readUpTo(MAX_SUB_BYTES + 1) }
            check(bytes.size <= MAX_SUB_BYTES) { "Подписка слишком большая" }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
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
                    .put("provider", it.provider ?: JSONObject.NULL)
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
                if (o.isNull("provider")) null else o.optString("provider").ifBlank { null }
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
