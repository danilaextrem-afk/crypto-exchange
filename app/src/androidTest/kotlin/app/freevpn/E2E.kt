package app.freevpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.freevpn.core.RoutingMode
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.fail
import java.net.HttpURLConnection
import java.net.URL

/**
 * Общие помощники сквозных тестов.
 *
 * Тесты запускаются на эмуляторе в CI против НАСТОЯЩЕГО WireGuard-сервера, который
 * поднимает server/setup-wireguard.sh на машине сборки (эмулятор видит её как 10.0.2.2).
 * Конфиги клиентов передаются аргументами инструментации wgConf1/wgConf2 (base64).
 * Без них тесты пропускаются (assume), а не «проходят».
 */
object E2E {
    const val TAG = "FreeVpnE2E"
    private const val SHOTS = "/data/local/tmp/freevpn-e2e"

    val app: FreeVpnApp get() = ApplicationProvider.getApplicationContext()
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    fun conf(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
        ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }

    fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Разрешение VPN без диалога (так делают все CI для VPN-приложений). */
    fun grantVpnConsent() {
        shell("appops set ${app.packageName} ACTIVATE_VPN allow")
    }

    fun screenshot(name: String) {
        shell("mkdir -p $SHOTS")
        shell("screencap -p $SHOTS/$name.png")
        Log.i(TAG, "SCREENSHOT $name")
    }

    /** Сигнал машине сборки (её скрипт читает logcat): STOP_SERVER / START_SERVER. */
    fun signalHost(command: String) {
        Log.i(TAG, command)
    }

    fun journal(): String = app.vpn.log.value.takeLast(40).joinToString("\n")

    fun awaitState(timeoutMs: Long, what: String, cond: (VpnState) -> Boolean): VpnState = runBlocking {
        withTimeoutOrNull(timeoutMs) { app.vpn.state.first(cond) }
            ?: run {
                fail("Не дождались: $what за ${timeoutMs / 1000} с. Состояние: ${app.vpn.state.value}\nЖурнал:\n${journal()}")
                throw IllegalStateException()
            }
    }

    fun awaitTrue(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(500)
        }
        fail("Не дождались: $what за ${timeoutMs / 1000} с. Состояние: ${app.vpn.state.value}\nЖурнал:\n${journal()}")
    }

    /** Приводит приложение в исходное состояние: VPN выключен, серверов нет, режим «Всё через VPN». */
    fun reset() {
        app.vpn.disconnect()
        awaitState(30_000, "отключение") { it is VpnState.Disconnected }
        Thread.sleep(1000) // дать завершиться остановке туннеля
        app.repo.servers.value.forEach { app.repo.remove(it.id) }
        app.prefs.mode = RoutingMode.ALL
        app.prefs.autoSelect = true
        app.prefs.selectedServerId = null
        app.prefs.vpnApps = emptySet()
        app.prefs.directApps = emptySet()
    }

    private val cm: ConnectivityManager get() = app.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun vpnNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Сеть по умолчанию для этого приложения — VPN? (приложение всегда идёт через туннель) */
    fun activeIsVpn(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    @Suppress("DEPRECATION")
    fun physicalNetworks(): List<String> = cm.allNetworks.mapNotNull { n ->
        val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) null
        else when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    /** HTTPS-запрос через сеть по умолчанию. null — запрос не прошёл. */
    fun fetch(url: String, timeoutMs: Int = 8000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    /**
     * Диапазоны UID, которые система пускает в VPN-сеть (из dumpsys connectivity).
     * Это то, что реально применил Android после VpnService.Builder — проверка Split Tunneling.
     */
    fun vpnUidRanges(): List<LongRange> {
        val dump = shell("dumpsys connectivity")
        val line = dump.lineSequence().firstOrNull { "Transports: VPN" in it && "Uids:" in it }
            ?: fail("В dumpsys connectivity нет VPN-сети с Uids").let { "" }
        val body = Regex("Uids: <\\{([^}]*)\\}>").find(line)?.groupValues?.get(1)
            ?: fail("Не удалось разобрать Uids в строке: ${line.take(400)}").let { "" }
        return body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val m = Regex("(\\d+)(?:-(\\d+))?").matchEntire(part) ?: error("Непонятный диапазон: $part")
            val a = m.groupValues[1].toLong()
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toLong()
            a..b
        }
    }
}
