package dev.cpa.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.feature.remote.RemoteNavigation
import dev.cpa.android.core.platform.LocalNetworkPermissionGate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as CpaApplication
            dev.cpa.android.core.localization.AppLanguageResources {
            CpaTheme {
                LocalNetworkPermissionGate(onGranted = app.remote::onForeground) {
                    RemoteNavigation(app.remote)
                }
            }
            }
        }
    }
}
