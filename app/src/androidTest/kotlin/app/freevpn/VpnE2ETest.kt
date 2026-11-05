package app.freevpn

import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.freevpn.E2E.TAG
import app.freevpn.E2E.TRACE
import app.freevpn.E2E.activeIsVpn
import app.freevpn.E2E.app
import app.freevpn.E2E.awaitState
import app.freevpn.E2E.awaitTrue
import app.freevpn.E2E.fetch
import app.freevpn.E2E.vpnNetwork
import app.freevpn.core.RoutingMode
import app.freevpn.vpn.InstalledApps
import app.freevpn.vpn.VpnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.InetAddress

/**
 * Сквозные проверки VPN на эмуляторе против настоящего WireGuard-сервера.
 * Ни одна проверка не подменяет туннель: если сервер недоступен, тесты падают.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VpnE2ETest {
    private lateinit var conf1: String
    private lateinit var conf2: String

    @Before fun setUp() {
        val c1 = E2E.conf("wgConf1")
        val c2 = E2E.conf("wgConf2")
        assumeTrue("Нет конфигов тестового сервера (wgConf1/wgConf2) — тесты пропущены", c1 != null && c2 != null)
        conf1 = c1!!
        conf2 = c2!!
        E2E.grantVpnConsent()
        assertNull("Разрешение VPN не выдано", VpnService.prepare(app))
        E2E.reset()
    }

    @After fun tearDown() {
        if (::conf1.isInitialized) E2E.shell("svc wifi enable")
        runCatching { E2E.reset() }
    }

    private fun connected(timeoutMs: Long = 90_000) =
        awaitState(timeoutMs, "состояние «Подключено»") { it is VpnState.Connected } as VpnState.Connected

    @Test fun t1_connectsThroughRealServer_dnsAndStatus() {
        // До подключения приложение ходит в сеть напрямую.
        assertFalse(activeIsVpn())
        val direct = fetch(TRACE)
        assertNotNull("Нет интернета на эмуляторе даже без VPN", direct)

        app.repo.importText(conf1)
        app.vpn.connect()
        val st = connected()
        Log.i(TAG, "Подключено к ${st.server.name}")

        // Статус «Подключено» выставляется только после рукопожатия и реального трафика.
        assertTrue("Журнал не подтверждает проверку туннеля", app.vpn.log.value.any { "Подключено:" in it })
        assertTrue("Сеть по умолчанию — не VPN", activeIsVpn())
        val trace = fetch(TRACE)
        assertNotNull("HTTPS через туннель не работает", trace)
        Log.i(TAG, "trace через туннель:\n$trace")

        // DNS: у VPN-сети ровно DNS-серверы из конфига, имена разрешаются через туннель.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val dns = cm.getLinkProperties(vpnNetwork())!!.dnsServers.map { it.hostAddress }
        Log.i(TAG, "DNS VPN-сети: $dns")
        assertTrue("DNS из конфига не применены: $dns", "1.1.1.1" in dns && "9.9.9.9" in dns)
        assertTrue(InetAddress.getAllByName("example.com").isNotEmpty())

        // Живая статистика туннеля идёт.
        awaitTrue(20_000, "счётчики трафика туннеля") { app.vpn.live.value.totalRx > 0 && app.vpn.live.value.pingMs != null }

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(2500)
            E2E.screenshot("01-home-connected")
        }

        app.vpn.disconnect()
        awaitState(30_000, "отключение") { it is VpnState.Disconnected }
        awaitTrue(10_000, "исчезновение VPN-сети") { vpnNetwork() == null }
        assertFalse(activeIsVpn())
        assertNotNull("После отключения интернет не вернулся", fetch(TRACE))
    }

    @Test fun t2_failsOverFromDeadServerToBackup() {
        // «Мёртвый» сервер: тот же ключ, но порт, на котором никто не слушает.
        val dead = conf1.replace(Regex("(?m)^Endpoint\\s*=.*$"), "Endpoint = 10.0.2.2:51999")
            .replace(Regex("(?m)^# Name:.*$"), "# Name: Недоступный")
        app.repo.importText(dead)
        app.repo.importText(conf2)
        val deadId = app.repo.servers.value.first { it.name == "Недоступный" }.id
        val liveId = app.repo.servers.value.first { it.id != deadId }.id
        // Ручной выбор недоступного: он пробуется первым, остальные — резервные.
        app.prefs.autoSelect = false
        app.prefs.selectedServerId = deadId

        app.vpn.connect()
        val st = connected(120_000)
        assertEquals("Подключились не к резервному серверу", liveId, st.server.id)
        assertTrue("В журнале нет неудачи первого сервера", app.vpn.log.value.any { "Недоступный: нет рукопожатия" in it })
        assertTrue((app.repo.stats.value[deadId]?.consecutiveFailures ?: 0) > 0)
        assertNotNull(fetch(TRACE))
    }

    @Test fun t3_reconnectsAfterNetworkSwitch() {
        app.repo.importText(conf1)
        app.vpn.connect()
        connected()
        Log.i(TAG, "Сети до переключения: ${E2E.physicalNetworks()}")

        val before = app.vpn.log.value.size
        E2E.shell("svc wifi disable")
        awaitTrue(30_000, "реакция на смену сети") {
            app.vpn.log.value.drop(before).any { "Смена сети" in it || "Нет сети" in it }
        }
        Thread.sleep(3000)
        val others = E2E.physicalNetworks()
        Log.i(TAG, "Сети без Wi-Fi: $others")
        if (others.isNotEmpty()) {
            // Есть мобильная сеть эмулятора: туннель должен подняться через неё.
            connected(90_000)
            assertTrue(activeIsVpn())
            assertNotNull("Нет интернета через туннель по мобильной сети", fetch(TRACE))
            E2E.screenshot("02-home-after-wifi-off")
        } else {
            Log.i(TAG, "В эмуляторе нет мобильной сети — проверяем только возврат Wi-Fi")
        }

        val mid = app.vpn.log.value.size
        E2E.shell("svc wifi enable")
        awaitTrue(60_000, "реакция на возврат Wi-Fi") {
            app.vpn.log.value.drop(mid).any { "Смена сети" in it || "Сеть изменилась" in it || "Подключено:" in it }
        }
        connected(120_000)
        awaitTrue(30_000, "интернет через туннель после возврата Wi-Fi") { fetch(TRACE, 5000) != null }
        assertTrue(activeIsVpn())
    }

    @Test fun t4_serverDown_trafficBlocked_thenRecovers() {
        app.repo.importText(conf1)
        app.repo.importText(conf2)
        app.vpn.connect()
        connected()

        // Машина сборки останавливает WireGuard: оба сервера (один хост) становятся недоступны.
        E2E.signalHost("STOP_SERVER")
        val st = awaitState(180_000, "«Трафик заблокирован» после падения сервера") {
            it is VpnState.Reconnecting && "заблокирован" in it.reason
        } as VpnState.Reconnecting
        Log.i(TAG, "Состояние при недоступных серверах: ${st.reason}")

        // Главное: пока серверов нет, трафик НЕ уходит напрямую через оператора.
        assertTrue("VPN-сеть исчезла — трафик пошёл бы мимо туннеля", activeIsVpn())
        assertNull("Запрос прошёл, хотя сервер недоступен: утечка мимо VPN", fetch(TRACE, 5000))
        E2E.screenshot("03-home-servers-down")

        E2E.signalHost("START_SERVER")
        connected(180_000)
        awaitTrue(30_000, "интернет после восстановления сервера") { fetch(TRACE, 5000) != null }
    }

    @Test fun t5_splitTunneling_appliedBySystem() {
        val own = app.applicationInfo.uid.toLong()
        val pm = app.packageManager
        val launcher = InstalledApps.launcherPackages(pm) - app.packageName
        val bypass = listOf("com.android.settings", "com.android.chrome", "com.google.android.youtube")
            .firstOrNull { it in launcher } ?: launcher.first()
        val bypassUid = pm.getApplicationInfo(bypass, 0).uid.toLong()
        Log.i(TAG, "Исключаем $bypass (uid $bypassUid), наш uid $own")

        app.repo.importText(conf1)

        // Режим «Российские приложения напрямую» + выбранное приложение напрямую.
        app.prefs.mode = RoutingMode.RU_DIRECT
        app.prefs.directApps = setOf(bypass)
        app.vpn.connect()
        connected()
        var ranges = E2E.vpnUidRanges()
        Log.i(TAG, "RU_DIRECT: Uids VPN = $ranges")
        assertTrue("Наше приложение выпало из туннеля", ranges.any { own in it })
        assertFalse("Исключённое приложение всё ещё в туннеле", ranges.any { bypassUid in it })
        assertTrue(activeIsVpn())

        // Режим «Ручной выбор» без отмеченных приложений: в туннеле только само приложение.
        app.prefs.mode = RoutingMode.MANUAL
        app.prefs.vpnApps = emptySet()
        app.vpn.connect()
        connected()
        awaitTrue(15_000, "применение нового списка") {
            runCatching { E2E.vpnUidRanges() }.getOrNull()?.let { r -> r.none { bypassUid in it } && r.sumOf { it.last - it.first + 1 } < 100 } == true
        }
        ranges = E2E.vpnUidRanges()
        Log.i(TAG, "MANUAL: Uids VPN = $ranges")
        assertTrue(ranges.any { own in it })
        assertFalse(ranges.any { bypassUid in it })
        assertNotNull("Через туннель не работает интернет в ручном режиме", fetch(TRACE))
    }
}
