package app.freevpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import app.freevpn.FreeVpnApp

private enum class Tab(val label: String, val mark: String) {
    HOME("Главная", "●"),
    SERVERS("Серверы", "☷"),
    ROUTES("Маршруты", "⇄"),
    DIAG("Проверка", "✚"), // «Диагностика» не помещалась в нижнее меню и обрезалась
    SETTINGS("Ещё", "⋯")
}

@Composable
fun AppRoot(app: FreeVpnApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    val current = Tab.valueOf(tab)
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = t == current,
                        onClick = { tab = t.name },
                        icon = { Text(t.mark, fontSize = 18.sp) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = TextLo, unselectedTextColor = TextLo,
                            indicatorColor = Surface2
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Bg).padding(padding)) {
            when (current) {
                Tab.HOME -> HomeScreen(app)
                Tab.SERVERS -> ServersScreen(app)
                Tab.ROUTES -> RoutingScreen(app)
                Tab.DIAG -> DiagnosticsScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            }
        }
    }
}
