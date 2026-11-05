package app.freevpn.core

enum class RoutingMode(val title: String, val description: String) {
    ALL("Всё через VPN", "Весь трафик устройства идёт через VPN."),
    RU_DIRECT(
        "Российские приложения напрямую",
        "Известные российские приложения обходят VPN, всё остальное идёт через VPN."
    ),
    MANUAL("Ручной выбор", "Через VPN идут только приложения, которые вы отметите.")
}

data class ServerProfile(
    val id: String,
    val name: String,
    val country: String,
    val config: String,
    val load: Int? = null,
    val source: String = "local"
) {
    /** Хост или IP из строки Endpoint конфигурации WireGuard. */
    val endpointHost: String?
        get() = ENDPOINT.find(config)?.groupValues?.get(1)?.trim()?.removePrefix("[")?.removeSuffix("]")

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

data class SplitPlan(val include: Set<String>, val exclude: Set<String>) {
    companion object {
        val NONE = SplitPlan(emptySet(), emptySet())
    }
}
