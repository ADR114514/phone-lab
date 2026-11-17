package com.mobilelab.vibrodiag.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mobilelab.core.permission.rememberPermissionRequester
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen

/**
 * Lightweight navigation host. The activity handles configuration changes itself,
 * so a plain observable screen enum is enough and keeps the app dependency free.
 */
@Composable
fun VibroDiagApp(state: AppState) {
    val context = LocalContext.current
    val permission = rememberPermissionRequester(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(permission.allGranted) {
        state.refreshCapabilities()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        state.clearMessage()
    }

    BackHandler(enabled = state.screen != Screen.Home && state.activeRecording == null) {
        state.screen = Screen.Home
    }

    when (state.screen) {
        Screen.Home -> HomeScreen(state, permission)
        Screen.Record -> RecordScreen(state, permission)
        Screen.BlindTest -> BlindTestScreen(state, permission)
        Screen.Evaluation -> EvaluationScreen(state)
        Screen.Dataset -> DatasetScreen(state)
        Screen.Settings -> SettingsScreen(state)
    }
}
