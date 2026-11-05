package app.freevpn.core

/**
 * Провайдеры, которые официально разрешают подключаться к своим серверам
 * сторонним WireGuard-клиентам. Серверы в приложение не встраиваются:
 * пользователь скачивает конфиги в своём аккаунте у провайдера и импортирует их.
 */
enum class Provider(val id: String, val title: String, val limits: String?) {
    PROTON_FREE(
        "proton-free", "Proton VPN Free",
        "Бесплатный тариф Proton: одно подключение на аккаунт одновременно, около 8 стран, " +
            "скорость не гарантируется. Новые серверы — только новыми конфигами из вашего аккаунта Proton."
    ),
    PROTON("proton", "Proton VPN", null);

    companion object {
        fun byId(id: String?): Provider? = entries.firstOrNull { it.id == id }
    }
}

object ProviderDetector {
    data class Hint(val provider: Provider, val name: String, val country: String?)

    // Имена серверов Proton: "NL-FREE#187118", "US-NY#12", "CH-NL#1".
    private val PROTON_NAME = Regex("""\b([A-Z]{2})(?:-([A-Z]{2,4}))?#(\d+)\b""")
    // Имя файла при скачивании: "...-NL-FREE-187118.conf".
    private val PROTON_FILE = Regex("""\b([A-Z]{2})(?:-([A-Z]{2,4}))?-(\d+)(?:\.CONF)?$""")

    /** Распознаёт конфиг Proton по их внутренней сети (10.2.0.2/32, DNS 10.2.0.1). */
    fun detect(config: String, fileName: String? = null): Hint? {
        val protonNet = Regex("(?im)^\\s*Address\\s*=\\s*10\\.2\\.0\\.2/32").containsMatchIn(config) &&
            Regex("(?im)^\\s*DNS\\s*=\\s*10\\.2\\.0\\.1\\b").containsMatchIn(config)
        if (!protonNet) return null
        val m = PROTON_NAME.find(config)
        val f = if (m == null) fileName?.uppercase()?.trim()?.let { PROTON_FILE.find(it) } else null
        val country = (m ?: f)?.groupValues?.get(1)
        val tag = (m ?: f)?.groupValues?.get(2).orEmpty()
        val num = (m ?: f)?.groupValues?.get(3)
        val free = tag == "FREE"
        val label = when {
            country == null -> "Proton"
            tag.isEmpty() -> "Proton $country #$num"
            else -> "Proton $country-$tag #$num"
        }
        return Hint(if (free) Provider.PROTON_FREE else Provider.PROTON, label, country)
    }
}
