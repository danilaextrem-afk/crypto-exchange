package app.freevpn.core

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

data class Registration(
    val address: String,
    val serverPublicKey: String,
    val endpoint: String,
    val dns: List<String>,
    val mtu: Int,
    val keepalive: Int
)

/** Протокол общения с узлом (server/node): разбор ответа, сборка конфига, проверка отпечатка. */
object NodeProtocol {
    private val KEY = Regex("^[A-Za-z0-9+/]{43}=$")
    private val ADDRESS = Regex("^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$")
    private val ENDPOINT = Regex("^([A-Za-z0-9.-]{1,253}):(\\d{1,5})$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isValidKey(k: String): Boolean = KEY.matches(k)

    /** Отпечаток — это base64 от 32 байт (SHA-256), т.е. 44 символа. */
    fun isValidPin(p: String): Boolean = KEY.matches(p)

    private fun isIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    }

    /**
     * Разбирает ответ /v1/register. Каждое поле проверяется строго, потому что значения
     * попадают в текст конфигурации: перевод строки в поле дал бы подмену настроек.
     */
    fun parseRegistration(json: String): Registration {
        val o = JSONObject(json)
        val address = o.getString("address")
        require(ADDRESS.matches(address) && isIpv4(address.substringBefore('/'))) { "Некорректный адрес от узла" }
        val serverKey = o.getString("server_public_key")
        require(isValidKey(serverKey)) { "Некорректный ключ сервера" }
        val endpoint = o.getString("endpoint")
        val em = ENDPOINT.matchEntire(endpoint) ?: throw IllegalArgumentException("Некорректный endpoint")
        require(em.groupValues[2].toInt() in 1..65535) { "Некорректный порт" }
        val dnsArr = o.getJSONArray("dns")
        val dns = (0 until dnsArr.length()).map { dnsArr.getString(it) }
        require(dns.isNotEmpty() && dns.all { isIpv4(it) }) { "Некорректный DNS" }
        val mtu = o.optInt("mtu", 1380)
        require(mtu in 1200..1500) { "Некорректный MTU" }
        val keepalive = o.optInt("keepalive", 25)
        require(keepalive in 0..120) { "Некорректный keepalive" }
        return Registration(address, serverKey, endpoint, dns, mtu, keepalive)
    }

    /** Полный туннель IPv4+IPv6; DNS идёт внутри туннеля. */
    fun buildConfig(privateKey: String, r: Registration): String {
        require(isValidKey(privateKey)) { "Некорректный приватный ключ" }
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = ${r.address}")
            appendLine("DNS = ${r.dns.joinToString(", ")}")
            appendLine("MTU = ${r.mtu}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${r.serverPublicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${r.endpoint}")
            appendLine("PersistentKeepalive = ${if (r.keepalive in 1..120) r.keepalive else 25}")
        }.trim()
    }

    /** Отпечаток: Base64(SHA-256(DER открытого ключа сертификата)) — как у server/node. */
    fun spkiPin(spkiDer: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

    fun pinMatches(spkiDer: ByteArray, expectedPin: String): Boolean =
        MessageDigest.isEqual(spkiPin(spkiDer).toByteArray(), expectedPin.toByteArray())
}
