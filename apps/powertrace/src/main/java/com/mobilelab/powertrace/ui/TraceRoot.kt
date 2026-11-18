package com.mobilelab.powertrace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SystemAccess

/** 5 个页面。 */
enum class Route { HOME, PREFLIGHT, WIZARD, SAMPLING, RESULTS, SETTINGS }

/**
 * 根导航：单 Activity 内用可保存的 route 字符串做导航，
 * 旋转/进程重建后仍停留在同一页面（采集本身由前台服务保证存活）。
 */
@Composable
fun TraceRoot() {
    val context = LocalContext.current
    val campaign by PowerTraceController.state.collectAsState()
    val statusLine by PowerTraceController.statusLine.collectAsState()

    var routeName by rememberSaveable { mutableStateOf(Route.HOME.name) }
    var accessTick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val loaded = PowerTraceController.loadFromDisk()
        // 进程被杀/崩溃恢复后重新打开 App：若批次仍在 RUNNING，把前台服务重新拉起继续采样，
        // 否则精确闹钟已随进程消失，30 min 工况会漏点。
        if (loaded?.status == com.mobilelab.powertrace.data.CampaignStatus.RUNNING) {
            com.mobilelab.powertrace.service.SamplingControl.sampleNow(context)
        }
    }

    // 从系统设置页返回后刷新权限状态。
    val access = remember(accessTick) { SystemAccess.snapshot(context) }
    val device = remember(accessTick) { PowerTraceController.deviceSnapshotInfo() }

    val route = Route.entries.firstOrNull { it.name == routeName } ?: Route.HOME
    val navigate: (Route) -> Unit = { routeName = it.name }

    BackHandler(enabled = route != Route.HOME) {
        routeName = when (route) {
            Route.PREFLIGHT, Route.WIZARD, Route.SETTINGS -> Route.HOME.name
            Route.SAMPLING -> Route.HOME.name
            Route.RESULTS -> Route.HOME.name
            Route.HOME -> Route.HOME.name
        }
    }

    when (route) {
        Route.PREFLIGHT -> PreflightScreen(
            access = access,
            onRefresh = { accessTick++ },
            onPassed = {
                accessTick++
                navigate(Route.WIZARD)
            },
            onBack = { navigate(Route.HOME) },
        )

        Route.HOME -> HomeScreen(
            campaign = campaign,
            access = access,
            device = device,
            statusLine = statusLine,
            onNavigate = navigate,
        )

        Route.WIZARD -> {
            val preflightPassed = access.notifications && access.batteryUnrestricted && access.exactAlarm &&
                PowerTraceController.isOemBackgroundConfirmed() && PowerTraceController.isOemSleepConfirmed()
            if (preflightPassed) {
                WizardScreen(
                    access = access,
                    onBack = { navigate(Route.HOME) },
                    onStarted = { navigate(Route.SAMPLING) },
                )
            } else {
                PreflightScreen(
                    access = access,
                    onRefresh = { accessTick++ },
                    onPassed = {
                        // 通过后必须刷新 access 快照并触发重组，否则 route 仍是 WIZARD，界面不会离开检查页。
                        accessTick++
                        navigate(Route.WIZARD)
                    },
                    onBack = { navigate(Route.HOME) },
                )
            }
        }

        Route.SAMPLING -> SamplingScreen(
            campaign = campaign,
            onBack = { navigate(Route.HOME) },
            onFinished = { navigate(Route.RESULTS) },
        )

        Route.RESULTS -> ResultsScreen(
            campaign = campaign,
            onBack = { navigate(Route.HOME) },
            onRerunStarted = { navigate(Route.SAMPLING) },
        )

        Route.SETTINGS -> SettingsScreen(
            access = access,
            campaign = campaign,
            onBack = { navigate(Route.HOME) },
        )
    }
}
