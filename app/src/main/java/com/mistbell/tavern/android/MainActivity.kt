package com.mistbell.tavern.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mistbell.tavern.android.navigation.AppNavigation
import com.mistbell.tavern.android.ui.theme.MistbellThemeWithSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MistbellThemeWithSettings {
                AppNavigation()
            }
        }
    }
}
