package app.freevpn.data

import app.freevpn.core.NodeProtocol
import app.freevpn.core.Registration
import app.freevpn.core.readUpTo
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Доверяет единственному сертификату узла — тому, чей отпечаток указан в каталоге. */
class PinTrustManager(private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        throw CertificateException("Клиентские сертификаты не поддерживаются")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Сервер не прислал сертификат")
        if (!NodeProtocol.pinMatches(leaf.publicKey.encoded, pin)) {
            throw CertificateException("Отпечаток сертификата не совпал с каталогом")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

data class NodeHealth(val load: Int?, val peers: Int?)

object NodeClient {
    private fun open(url: String, pin: String, connectMs: Int, readMs: Int): HttpsURLConnection {
        val c = URL(url).openConnection() as HttpsURLConnection
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(PinTrustManager(pin)), null)
        c.sslSocketFactory = ctx.socketFactory
        // Имя в сертификате не важно: узел адресуется по IP, а подлинность проверяет отпечаток.
        c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.useCaches = false
        return c
    }

    /** Состояние узла. Бросает исключение, если узел недоступен или отпечаток не совпал. */
    fun health(api: String, pin: String): NodeHealth {
        val c = open("$api/v1/health", pin, 3000, 3000)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val o = JSONObject(String(c.inputStream.use { it.readUpTo(4096) }, Charsets.UTF_8))
            check(o.optBoolean("ok", false)) { "Узел сообщил об ошибке" }
            return NodeHealth(
                if (o.has("load")) o.optInt("load") else null,
                if (o.has("peers")) o.optInt("peers") else null
            )
        } finally {
            c.disconnect()
        }
    }

    /** Регистрирует публичный ключ устройства и возвращает параметры туннеля. */
    fun register(api: String, pin: String, publicKey: String): Registration {
        val c = open("$api/v1/register", pin, 5000, 8000)
        try {
            val body = JSONObject().put("public_key", publicKey).toString().toByteArray()
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            when (val code = c.responseCode) {
                200 -> Unit
                429 -> throw IOException("Слишком много запросов к узлу, попробуйте позже")
                503 -> throw IOException("Узел переполнен")
                else -> throw IOException("Узел ответил HTTP $code")
            }
            return NodeProtocol.parseRegistration(String(c.inputStream.use { it.readUpTo(8192) }, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    /** Задержка до узла: лучшее из трёх TCP-подключений. null — порт недоступен. */
    fun tcpRtt(api: String): Long? {
        val uri = URI(api)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        var best: Long? = null
        repeat(3) {
            try {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(host, port), 2500)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    if (best == null || ms < best!!) best = ms
                }
            } catch (e: IOException) {
                // следующая попытка
            }
        }
        return best
    }
}
