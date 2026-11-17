package com.mobilelab.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun missingPermissions(context: Context, permissions: List<String>): List<String> =
    permissions.filter { !hasPermission(context, it) }

/** Small handle that the UI keeps around to request runtime permissions on demand. */
class PermissionRequester internal constructor(
    val permissions: List<String>,
    val allGranted: Boolean,
    private val launchRequest: (callback: (Boolean) -> Unit) -> Unit,
) {
    fun request(onResult: (Boolean) -> Unit = {}) = launchRequest(onResult)
}

/**
 * Tracks a permission set across recompositions and Activity resumes so that a
 * grant made in the system settings screen is picked up when the user returns.
 */
@Composable
fun rememberPermissionRequester(vararg permissions: String): PermissionRequester {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(permissions.all { hasPermission(context, it) }) }
    var callback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val nowGranted = permissions.all { hasPermission(context, it) }
        granted = nowGranted
        callback?.invoke(nowGranted)
        callback = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = permissions.all { hasPermission(context, it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return PermissionRequester(permissions.toList(), granted) { cb ->
        callback = cb
        val missing = permissions.filter { !hasPermission(context, it) }
        if (missing.isEmpty()) {
            granted = true
            cb(true)
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun openLocationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun isLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    return lm.isLocationEnabled
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Asks the user to exclude this app from Doze. Returns false if no activity handled it. */
fun requestIgnoreBatteryOptimizations(context: Context): Boolean = runCatching {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

/** Android 12+ exact alarms are a separate user grant; used by long standby runs. */
fun canScheduleExactAlarms(context: Context): Boolean {
    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
}

fun requestExactAlarmPermission(context: Context): Boolean = runCatching {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

object Permissions {
    val recordAudio: String = Manifest.permission.RECORD_AUDIO
    val camera: String = Manifest.permission.CAMERA
    val fineLocation: String = Manifest.permission.ACCESS_FINE_LOCATION
    val coarseLocation: String = Manifest.permission.ACCESS_COARSE_LOCATION
    val postNotifications: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else ""
}
