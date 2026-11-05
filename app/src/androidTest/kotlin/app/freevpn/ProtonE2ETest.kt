package app.freevpn

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.freevpn.E2E.TAG
import app.freevpn.E2E.TRACE
import app.freevpn.E2E.activeIsVpn
import app.freevpn.E2E.app
import app.freevpn.E2E.awaitState
import app.freevpn.E2E.awaitTrue
import app.freevpn.E2E.fetch
import app.freevpn.vpn.VpnState
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подключение к НАСТОЯЩИМ бесплатным серверам Proton VPN.
 * Конфиги приходят из секрета репозитория PROTON_CONFS (base64 через запятую) и нигде не сохраняются.
 * Без секрета тест пропускается — подменять серверы нечем и незачем.
 * Замеры сделаны с машины GitHub Actions (дата-центр), а не с телефона у вашего оператора.
 */
@RunWith(AndroidJUnit4::class)
class ProtonE2ETest {
    @After fun tearDown() { runCatching { E2E.reset() } }

    @Test fun connectsToRealProtonFreeServers_measuresLatencyAndSpeed() {
        val raw = InstrumentationRegistry.getArguments().getString("protonConfs")
        assumeTrue("Нет секрета PROTON_CONFS — проверка Proton пропущена", !raw.isNullOrBlank())
        val confs = raw!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }

        E2E.grantVpnConsent()
        E2E.reset()
        confs.forEach { app.repo.importText(it) }
        val servers = app.repo.servers.value
        Log.i(TAG, "PROTON: импортировано ${servers.size}: ${servers.map { "${it.name} [${it.provider}]" }}")
        assertTrue("Конфиги не распознаны как Proton", servers.all { it.provider?.startsWith("proton") == true })

        app.prefs.autoSelect = true
        app.vpn.connect()
        val st = awaitState(150_000, "подключение к Proton") { it is VpnState.Connected } as VpnState.Connected
        assertTrue("Сеть по умолчанию — не VPN", activeIsVpn())

        val trace = fetch(TRACE, 10_000)
        assertNotNull("HTTPS через Proton не работает", trace)
        val ip = trace!!.lineSequence().firstOrNull { it.startsWith("ip=") }
        val loc = trace.lineSequence().firstOrNull { it.startsWith("loc=") }
        Log.i(TAG, "PROTON: подключено к ${st.server.name}; внешний $ip $loc")

        awaitTrue(30_000, "задержка через туннель") { app.vpn.live.value.pingMs != null }
        Log.i(TAG, "PROTON: задержка (TCP до 1.1.1.1:443 через туннель) = ${app.vpn.live.value.pingMs} мс")

        // Скорость: загрузка 25 МБ через туннель.
        val bytes = 25_000_000
        val t0 = System.nanoTime()
        val got = runCatching {
            val c = URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.inputStream.use { s -> val buf = ByteArray(64 * 1024); var n = 0L; while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
        }.getOrDefault(0L)
        val sec = (System.nanoTime() - t0) / 1e9
        Log.i(TAG, "PROTON: загрузка ${got} байт за ${"%.1f".format(sec)} с = ${"%.1f".format(got * 8 / sec / 1e6)} Мбит/с")
        assertTrue("Загрузка через Proton не прошла", got > 1_000_000)

        // Пробегаем остальные серверы: «Измерить все» реально поднимает туннель к каждому.
        app.vpn.disconnect()
        awaitState(30_000, "отключение") { it is VpnState.Disconnected }
        app.vpn.benchmark()
        awaitTrue(15_000, "старт замера") { app.vpn.bench.value.running }
        awaitTrue(300_000, "замер всех серверов") { !app.vpn.bench.value.running && app.vpn.bench.value.text.isNotEmpty() }
        app.repo.servers.value.forEach { s ->
            val x = app.repo.stats.value[s.id]
            Log.i(TAG, "PROTON: ${s.name}: задержка ${x?.latencyMs?.toInt()} мс, ↓ ${x?.downMbps?.let { "%.1f".format(it) }} Мбит/с, сбоев ${x?.consecutiveFailures}")
        }
        E2E.screenshotApp("10-proton-servers")
    }
}
