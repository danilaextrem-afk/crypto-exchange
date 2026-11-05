package app.freevpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.freevpn.core.RoutingMode

class Prefs(ctx: Context) {
    private val plain: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val vault: SharedPreferences
    /** true — приватные ключи лежат в EncryptedSharedPreferences (Android Keystore). */
    val secureStorage: Boolean

    init {
        var sp: SharedPreferences
        var secure = true
        try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            sp = EncryptedSharedPreferences.create(
                ctx, "vault", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore бывает сломан на отдельных прошивках. Показываем это в диагностике.
            sp = ctx.getSharedPreferences("vault_plain", Context.MODE_PRIVATE)
            secure = false
        }
        vault = sp
        secureStorage = secure
    }

    var mode: RoutingMode
        get() = runCatching { RoutingMode.valueOf(plain.getString("mode", null) ?: "RU_DIRECT") }
            .getOrDefault(RoutingMode.RU_DIRECT)
        set(v) = plain.edit().putString("mode", v.name).apply()

    var vpnApps: Set<String>
        get() = plain.getStringSet("vpnApps", emptySet())!!.toSet()
        set(v) = plain.edit().putStringSet("vpnApps", v).apply()

    var directApps: Set<String>
        get() = plain.getStringSet("directApps", emptySet())!!.toSet()
        set(v) = plain.edit().putStringSet("directApps", v).apply()

    /** Адреса подписки: по одному на строку; следующие — резервные зеркала. */
    var subscriptionUrls: String
        get() = plain.getString("subUrls", "") ?: ""
        set(v) = plain.edit().putString("subUrls", v).apply()

    var autoSelect: Boolean
        get() = plain.getBoolean("autoSelect", true)
        set(v) = plain.edit().putBoolean("autoSelect", v).apply()

    var autoConnectOnLaunch: Boolean
        get() = plain.getBoolean("autoConnect", false)
        set(v) = plain.edit().putBoolean("autoConnect", v).apply()

    var selectedServerId: String?
        get() = plain.getString("selectedServer", null)
        set(v) = plain.edit().putString("selectedServer", v).apply()

    var lastSubscriptionRefresh: Long
        get() = plain.getLong("subRefresh", 0L)
        set(v) = plain.edit().putLong("subRefresh", v).apply()

    var serversJson: String
        get() = vault.getString("servers", "[]") ?: "[]"
        set(v) = vault.edit().putString("servers", v).apply()

    var statsJson: String
        get() = plain.getString("stats", "{}") ?: "{}"
        set(v) = plain.edit().putString("stats", v).apply()
}
