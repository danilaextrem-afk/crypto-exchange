package app.freevpn

import android.net.VpnService
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.freevpn.E2E.TAG
import app.freevpn.E2E.TRACE
import app.freevpn.E2E.activeIsVpn
import app.freevpn.E2E.app
import app.freevpn.E2E.awaitState
import app.freevpn.E2E.fetch
import app.freevpn.vpn.VpnState
import kotlinx.coroutines.runBlocking
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

/**
 * Главный путь продукта против НАСТОЯЩЕГО узла server/node (Go), запущенного на машине сборки:
 * каталог → проба по HTTPS с пином → ключи создаются на устройстве → регистрация на узле →
 * туннель WireGuard → трафик. Ничего не подменяется: без узла тесты пропускаются, с узлом — проверяют.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NodeE2ETest {
    private lateinit var pin: String

    @Before fun setUp() {
        val p = E2E.conf("nodePin")?.trim()
        assumeTrue("Нет отпечатка узла (nodePin) — тест пропущен", !p.isNullOrEmpty())
        pin = p!!
        E2E.grantVpnConsent()
        assertNull("Разрешение VPN не выдано", VpnService.prepare(app))
        E2E.reset()
    }

    @After fun tearDown() {
        runCatching { E2E.reset() }
    }

    private fun node(id: String, name: String, pin: String) =
        """{"id":"$id","name":"$name","country":"DE","api":"https://10.0.2.2:8443","pin":"$pin"}"""

    private fun directory(vararg nodes: String) = """{"version":2,"nodes":[${nodes.joinToString(",")}]}"""

    @Test fun t1_probeRegisterAndConnectThroughNode() {
        assertEquals(1, app.repo.importText(directory(node("ci-node", "CI узел", pin))))

        // 1. Проба узла по HTTPS с проверкой пина: узел доступен, задержка измерена.
        runBlocking { app.repo.refreshProbes() }
        val probe = app.repo.probes.value["ci-node"]
        Log.i(TAG, "Проба узла: $probe")
        assertNotNull("Проба не выполнена", probe)
        assertTrue("Узел помечен недоступным: $probe", probe!!.up)
        assertNotNull("Не измерена задержка", probe.rttMs)

        // 2. Подключение одним вызовом: регистрация на узле, туннель, проверка трафика.
        app.prefs.autoSelect = true
        app.vpn.connect()
        val st = awaitState(120_000, "состояние «Подключено» через узел каталога") { it is VpnState.Connected } as VpnState.Connected
        Log.i(TAG, "Подключено через узел: ${st.server.name}")
        assertEquals("ci-node", st.server.id)
        assertTrue("Сеть по умолчанию — не VPN", activeIsVpn())
        val trace = fetch(TRACE)
        assertNotNull("HTTPS через туннель узла не работает", trace)
        Log.i(TAG, "trace через узел каталога:\n$trace")
        assertTrue("Конфигурация узла не сохранена", app.prefs.nodeCredsJson.contains("ci-node"))

        app.vpn.disconnect()
        awaitState(30_000, "отключение") { it is VpnState.Disconnected }
        assertFalse(activeIsVpn())
    }

    @Test fun t2_wrongPinIsRejected_andNothingIsSentToNode() {
        // Тот же узел, но отпечаток другой: TCP-соединение есть, а TLS по пину обязан отклониться.
        val wrong = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertTrue("Тест неверно настроен: пины совпали", wrong != pin)
        assertEquals(1, app.repo.importText(directory(node("bad-pin", "Чужой сертификат", wrong))))

        runBlocking { app.repo.refreshProbes() }
        val probe = app.repo.probes.value["bad-pin"]
        Log.i(TAG, "Проба с неверным пином: $probe")
        assertNotNull(probe)
        assertFalse("Узел с чужим сертификатом принят как доступный", probe!!.up)

        app.vpn.connect()
        awaitState(90_000, "отказ подключения к узлу с неверным пином") { it is VpnState.Failed }
        assertFalse("VPN поднялся через узел с неверным пином", activeIsVpn())
        assertFalse("Ключи сохранены для непроверенного узла", app.prefs.nodeCredsJson.contains("bad-pin"))
    }
}
