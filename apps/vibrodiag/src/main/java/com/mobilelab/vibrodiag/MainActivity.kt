package com.mobilelab.vibrodiag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mobilelab.core.ui.theme.LabTheme
import com.mobilelab.vibrodiag.ui.VibroDiagApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = remember { AppState(applicationContext) }
            LabTheme {
                VibroDiagApp(state)
            }
            DisposableEffect(Unit) {
                onDispose { state.dispose() }
            }
        }
    }
}
