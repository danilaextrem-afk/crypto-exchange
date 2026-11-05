package app.freevpn.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Принимает либо один конфиг WireGuard (текст .conf), либо JSON-подписку:
 * {"version":1,"servers":[{"name":"Frankfurt","country":"DE","load":12,"config":"[Interface]\n..."}]}
 */
object SubscriptionParser {

    fun parse(raw: String, source: String = "local"): List<ServerProfile> {
        val text = raw.trim().removePrefix("﻿")
        require(text.isNotEmpty()) { "Пустой ввод" }
        return if (text.startsWith("{")) fromJson(text, source)
        else listOf(fromConfig(text, null, null, null, source))
    }

    private fun fromJson(text: String, source: String): List<ServerProfile> {
        val arr = JSONObject(text).optJSONArray("servers")
            ?: throw IllegalArgumentException("В подписке нет поля servers")
        val result = ArrayList<ServerProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val load = if (o.has("load")) o.optInt("load", -1).takeIf { it in 0..100 } else null
            result += fromConfig(
                o.getString("config"),
                o.optString("name").ifBlank { null },
                o.optString("country").ifBlank { null },
                load,
                source
            )
        }
        require(result.isNotEmpty()) { "Подписка не содержит серверов" }
        return result
    }

    fun fromConfig(
        configRaw: String,
        name: String?,
        country: String?,
        load: Int?,
        source: String
    ): ServerProfile {
        val config = configRaw.trim().replace("\r\n", "\n")
        validate(config)
        val commentName = Regex("(?im)^#\\s*name\\s*[:=]\\s*(.+)$").find(config)?.groupValues?.get(1)?.trim()
        val commentCountry = Regex("(?im)^#\\s*country\\s*[:=]\\s*(\\w{2})\\s*$").find(config)?.groupValues?.get(1)
        val probe = ServerProfile("", "", "", config)
        return ServerProfile(
            id = sha256(config).take(12),
            name = name ?: commentName ?: probe.endpointHost ?: "Сервер",
            country = (country ?: commentCountry ?: "??").uppercase().take(2),
            config = config,
            load = load,
            source = source
        )
    }

    fun validate(config: String) {
        fun has(regex: String) = Regex(regex, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(config)
        require(has("^\\s*\\[Interface\\]")) { "Нет секции [Interface]" }
        require(has("^\\s*\\[Peer\\]")) { "Нет секции [Peer]" }
        require(has("^\\s*PrivateKey\\s*=\\s*\\S+")) { "Нет PrivateKey" }
        require(has("^\\s*Address\\s*=\\s*\\S+")) { "Нет Address" }
        require(has("^\\s*PublicKey\\s*=\\s*\\S+")) { "Нет PublicKey" }
        require(has("^\\s*Endpoint\\s*=\\s*\\S+:\\d+")) { "Нет Endpoint (host:port)" }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
