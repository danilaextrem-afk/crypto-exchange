package app.freevpn

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.freevpn.core.ServerProfile
import app.freevpn.ui.AppRoot
import app.freevpn.ui.FreeVpnTheme
import app.freevpn.vpn.VpnState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FreeVpnApp

        app.repo.refreshIfStale()
        if (savedInstanceState == null &&
            app.prefs.autoConnectOnLaunch &&
            app.vpn.state.value is VpnState.Disconnected &&
            VpnService.prepare(this) == null
        ) {
            app.vpn.connect()
        }

        setContent { FreeVpnTheme { AppRoot(app) } }
    }
}
