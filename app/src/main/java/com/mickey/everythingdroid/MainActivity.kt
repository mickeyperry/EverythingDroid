package com.mickey.everythingdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mickey.everythingdroid.ui.SearchScreen
import com.mickey.everythingdroid.ui.SettingsScreen
import com.mickey.everythingdroid.ui.theme.EverythingDroidTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EverythingDroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(vm = vm, onBack = { showSettings = false })
                    } else {
                        SearchScreen(vm = vm, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
