package app.freevpn.core

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Каталог узлов (directory/directory.json). Он публичный: в нём нет секретов,
 * только адреса узлов и отпечатки их сертификатов.
 *
 *   {"version":2,"nodes":[{"id":"de-fra-1","name":"Frankfurt","country":"DE",
 *                          "api":"https://203.0.113.10","pin":"<base64 sha256 spki>"}]}
 */
object DirectoryParser {
    private val ID = Regex("^[a-z0-9-]{1,40}$")

    fun isDirectory(text: String): Boolean {
        val t = text.trim().removePrefix("﻿")
        return t.startsWith("{") && runCatching { JSONObject(t).has("nodes") }.getOrDefault(false)
    }

    /** Некорректные записи пропускаются, остальные работают. Сломанный JSON — ошибка. */
    fun parse(text: String): List<ServerProfile> {
        val arr = JSONObject(text.trim().removePrefix("﻿")).getJSONArray("nodes")
        val byId = LinkedHashMap<String, ServerProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val api = o.optString("api")
            val pin = o.optString("pin")
            val country = o.optString("country").uppercase(Locale.ROOT)
            if (!ID.matches(id)) continue
            if (!NodeProtocol.isValidPin(pin)) continue
            if (!country.matches(Regex("^[A-Z]{2}$"))) continue
            val uri = runCatching { URI(api) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null) continue
            byId[id] = ServerProfile(
                id = id,
                name = o.optString("name").ifBlank { id }.take(40),
                country = country,
                config = "",
                load = null,
                source = "dir",
                api = api.trimEnd('/'),
                pin = pin
            )
        }
        return byId.values.toList()
    }
}

object Countries {
    /** Название страны по двухбуквенному коду на русском; для неизвестного кода — сам код. */
    // Фиксированные названия: платформенные данные у разных устройств и JDK расходятся
    // (например, «Соединённые Штаты» вместо «США»), а интерфейс должен выглядеть одинаково.
    private val known = mapOf(
        "DE" to "Германия", "NL" to "Нидерланды", "FR" to "Франция", "PL" to "Польша", "US" to "США",
        "GB" to "Великобритания", "FI" to "Финляндия", "SE" to "Швеция", "CH" to "Швейцария",
        "AT" to "Австрия", "CZ" to "Чехия", "ES" to "Испания", "IT" to "Италия", "CA" to "Канада",
        "JP" to "Япония", "SG" to "Сингапур", "KZ" to "Казахстан", "AM" to "Армения",
        "TR" to "Турция", "AE" to "ОАЭ", "LV" to "Латвия", "LT" to "Литва", "EE" to "Эстония"
    )

    fun name(code: String): String {
        if (code.length != 2 || code == "??") return "Неизвестно"
        known[code.uppercase()]?.let { return it }
        val n =Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag("ru"))
        return if (n.isBlank() || n == code) code else n
    }
}
