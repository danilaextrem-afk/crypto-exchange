package app.freevpn

import android.app.Application
import app.freevpn.data.Prefs
import app.freevpn.data.ServerRepository
import app.freevpn.vpn.VpnManager
import com.wireguard.android.backend.GoBackend

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
        repo = ServerRepository(prefs)
        vpn = VpnManager(this, prefs, repo)
        // «Постоянный VPN»: Android сам запускает VPN-сервис (после перезагрузки и т. п.).
        GoBackend.setAlwaysOnCallback { vpn.onAlwaysOnStart() }
    }
}
