package app.freevpn

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.freevpn.E2E.app
import app.freevpn.E2E.awaitState
import app.freevpn.vpn.VpnState
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Проход по всем экранам приложения через настоящий интерфейс (UI Automator),
 * при подключении к настоящему серверу. Снимки экрана уходят в отчёт CI.
 */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun setUp() {
        val conf = E2E.conf("wgConf1")
        assumeTrue("Нет конфига тестового сервера — тест пропущен", conf != null)
        E2E.grantVpnConsent()
        E2E.reset()
        app.repo.importText(conf!!)
        app.repo.importText(E2E.conf("wgConf2") ?: conf)
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runCatching { E2E.reset() }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1500)
        E2E.dismissSystemDialogs()
        assertNotNull("Главный экран не открылся", device.wait(Until.findObject(By.text("FreeVPN")), 15_000))
    }

    /** Пункт нижнего меню: подпись может быть объединена со значком («☷, Серверы»). */
    private fun tab(label: String): UiObject2 {
        E2E.dismissSystemDialogs()
        val p = Pattern.compile("(?s).*$label")
        device.wait(Until.hasObject(By.text(p)), 10_000)
        return device.findObjects(By.text(p)).maxByOrNull { it.visibleBounds.top }
            ?: throw AssertionError("Нет вкладки $label")
    }

    /** contains = true для текста внутри кликабельных строк: Compose объединяет их подписи. */
    private fun waitText(text: String, timeoutMs: Long = 10_000, contains: Boolean = false): UiObject2 {
        val sel = if (contains) By.textContains(text) else By.text(text)
        device.wait(Until.findObject(sel), timeoutMs)?.let { return it }
        E2E.dismissSystemDialogs()
        return device.wait(Until.findObject(sel), 5_000) ?: throw AssertionError("Нет текста «$text» на экране")
    }

    @Test fun tourAllScreensWhileConnected() {
        launch()
        E2E.screenshot("10-home-disconnected")

        app.vpn.connect()
        awaitState(90_000, "подключение") { it is VpnState.Connected }
        waitText("Подключено")
        Thread.sleep(6000) // накопить ping/скорость
        E2E.screenshot("11-home-connected")

        tab("Серверы").click()
        waitText("Автовыбор", contains = true)
        E2E.screenshot("12-servers")

        tab("Маршруты").click()
        waitText("Российские приложения напрямую", contains = true)
        E2E.screenshot("13-routes")

        tab("Проверка").click()
        waitText("Диагностика")
        waitText("Запустить проверку").click()
        waitText("Выполняется…", 5_000)
        waitText("Запустить проверку", 120_000) // проверка закончилась
        E2E.screenshot("14-diagnostics-top")
        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1.0f)
        Thread.sleep(800)
        E2E.screenshot("15-diagnostics-bottom")

        tab("Ещё").click()
        waitText("Настройки")
        E2E.screenshot("16-settings")

        tab("Главная").click()
        waitText("Подключено")
        app.vpn.disconnect()
        awaitState(30_000, "отключение") { it is VpnState.Disconnected }
        waitText("Не подключено")
        E2E.screenshot("17-home-after-disconnect")
    }
}
