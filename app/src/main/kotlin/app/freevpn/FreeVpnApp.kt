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
        repo = ServerRepository(prefs)
        vpn = VpnManager(this, prefs, repo)
    }
}
