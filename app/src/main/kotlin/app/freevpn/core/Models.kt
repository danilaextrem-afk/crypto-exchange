package app.freevpn.core

import java.net.URI

enum class RoutingMode(val title: String, val description: String) {
    ALL("Всё через VPN", "Весь трафик устройства идёт через VPN."),
    RU_DIRECT(
        "Российские приложения напрямую",
        "Известные российские приложения обходят VPN, всё остальное идёт через VPN."
    ),
    MANUAL("Ручной выбор", "Через VPN идут только приложения, которые вы отметите.")
}

/**
 * Сервер в списке. Два вида:
 *  - ручной (config заполнен): конфиг WireGuard, добавленный вами или подпиской;
 *  - узел каталога (api и pin заполнены, config пуст): ключи генерируются на устройстве
 *    и регистрируются на узле автоматически при первом подключении.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val country: String,
    val config: String,
    val load: Int? = null,
    val source: String = "local",
    val api: String? = null,
    val pin: String? = null
) {
    val isNode: Boolean get() = api != null && pin != null

    /** Хост или IP из строки Endpoint конфигурации WireGuard. */
    val endpointHost: String?
        get() = ENDPOINT.find(config)?.groupValues?.get(1)?.trim()?.removePrefix("[")?.removeSuffix("]")

    /** Адрес сервера для сравнения с внешним IP: из конфига, а для узла каталога — из адреса API. */
    val publicHost: String?
        get() = endpointHost ?: api?.let { runCatching { URI(it).host }.getOrNull() }

    companion object {
        private val ENDPOINT = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+):(\\d+)\\s*$")
    }
}

data class ServerStats(
    val latencyMs: Double? = null,
    val lossPercent: Double = 0.0,
    val downMbps: Double? = null,
    val consecutiveFailures: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastTestedEpoch: Long = 0L
)

/** Результат проверки узла без туннеля: доступность, задержка до сервера, нагрузка. */
data class Probe(val up: Boolean, val rttMs: Long?, val load: Int?, val checkedAt: Long)

data class SplitPlan(val include: Set<String>, val exclude: Set<String>) {
    companion object {
        val NONE = SplitPlan(emptySet(), emptySet())
    }
}
