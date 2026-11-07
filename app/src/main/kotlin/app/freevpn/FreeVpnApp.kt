package app.freevpn

import android.app.Application
import app.freevpn.data.Prefs
import app.freevpn.data.ServerRepository
import app.freevpn.vpn.VpnManager

class FreeVpnApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: ServerRepository
        private set
    lateinit var vpn: VpnManager
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Каталог узлов, вшитый при сборке (directory/directory.json), и адреса его обновления.
        val bundled = runCatching {
            assets.open("directory.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val urls = BuildConfig.DIRECTORY_URLS.split(',', '\n', ' ').map { it.trim() }.filter { it.startsWith("https://") }
        repo = ServerRepository(prefs, bundled, urls)
        vpn = VpnManager(this, prefs, repo)
    }
}
