package com.mobilelab.signalmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilelab.core.permission.openAppSettings
import com.mobilelab.core.permission.openLocationSettings
import com.mobilelab.core.permission.rememberPermissionRequester
import com.mobilelab.signalmap.service.EnvironmentProbe
import com.mobilelab.signalmap.ui.HomeScreen
import com.mobilelab.signalmap.ui.MeasureScreen
import com.mobilelab.signalmap.ui.PointsScreen
import com.mobilelab.signalmap.ui.ResultScreen
import com.mobilelab.signalmap.ui.SettingsScreen
import com.mobilelab.signalmap.vm.MeasurementUi
import com.mobilelab.signalmap.vm.Screen
import com.mobilelab.signalmap.vm.SurveyViewModel

/**
 * Root composable: hand rolled navigation (single activity), permission plumbing
 * and the global "keep screen on while measuring" flag.
 */
@Composable
fun SignalMapApp(vm: SurveyViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val measurementPermissions = rememberPermissionRequester(
        *EnvironmentProbe.measurementPermissions().toTypedArray()
    )
    val cameraPermission = rememberPermissionRequester(EnvironmentProbe.cameraPermission())

    // Location switch / permission may be fixed in system settings; refresh when
    // the activity comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshEnvironment()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SOP requires the screen to stay on during a repeat (once per 30 s).
    val activity = context as? Activity
    val measuring = vm.measurement is MeasurementUi.Running
    DisposableEffect(activity, measuring) {
        if (activity != null && measuring) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (activity != null && measuring) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler(enabled = vm.canGoBack()) {
        vm.back()
    }

    val requestMeasurementPermissions: () -> Unit = {
        measurementPermissions.request { granted ->
            vm.refreshEnvironment()
            vm.postMessage(
                if (granted) {
                    "测量权限已授予"
                } else {
                    "权限未授予：请在系统设置中允许「位置信息」与「附近的 Wi-Fi 设备」"
                }
            )
        }
    }
    val requestCameraPermission: () -> Unit = {
        cameraPermission.request { granted ->
            vm.refreshEnvironment()
            vm.postMessage(if (granted) "相机权限已授予" else "相机权限未授予，可跳过拍照继续测量")
        }
    }
    val openLocation: () -> Unit = { openLocationSettings(context) }
    val openApp: () -> Unit = { openAppSettings(context) }
    val openWifi: () -> Unit = { openWifiSettings(context) }

    when (val screen = vm.screen) {
        is Screen.Home -> HomeScreen(
            vm = vm,
            environment = vm.environment,
            onMeasure = { pointId -> vm.enterMeasure(pointId) },
            onOpenPoints = { vm.navigate(Screen.Points) },
            onOpenResult = { vm.navigate(Screen.Result) },
            onOpenSettings = { vm.navigate(Screen.Settings) },
            onRequestPermissions = requestMeasurementPermissions,
            onRequestCameraPermission = requestCameraPermission,
            onOpenLocationSettings = openLocation,
            onOpenAppSettings = openApp,
            onOpenWifiSettings = openWifi,
        )

        is Screen.Points -> PointsScreen(
            vm = vm,
            onBack = { vm.back() },
        )

        is Screen.Measure -> MeasureScreen(
            vm = vm,
            pointId = screen.pointId,
            environment = vm.environment,
            onBack = {
                if (vm.measurement is MeasurementUi.Running) {
                    vm.postMessage("测量进行中，请等待本轮完成后再离开")
                } else {
                    vm.back()
                }
            },
            onOpenResult = { vm.navigate(Screen.Result) },
            onRequestPermissions = requestMeasurementPermissions,
            onRequestCameraPermission = requestCameraPermission,
            onOpenLocationSettings = openLocation,
            onOpenAppSettings = openApp,
            onOpenWifiSettings = openWifi,
        )

        is Screen.Result -> ResultScreen(
            vm = vm,
            onBack = { vm.back() },
        )

        is Screen.Settings -> SettingsScreen(
            vm = vm,
            onBack = { vm.back() },
        )
    }
}

private fun openWifiSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}