package com.mistbell.tavern.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mistbell.tavern.android.navigation.AppNavigation
import com.mistbell.tavern.android.ui.theme.MistbellThemeWithSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ 对 targetSdk 35+ 默认强制 edge-to-edge；旧系统保留显式兼容配置。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            enableEdgeToEdge()
        }
        setContent {
            MistbellThemeWithSettings {
                AppNavigation()
            }
        }
    }
}
