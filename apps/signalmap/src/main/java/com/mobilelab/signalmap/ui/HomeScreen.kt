package com.mobilelab.signalmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.data.DataContract
import com.mobilelab.signalmap.service.EnvironmentStatus
import com.mobilelab.signalmap.vm.SurveyViewModel

@Composable
fun HomeScreen(
    vm: SurveyViewModel,
    environment: EnvironmentStatus,
    onMeasure: (String) -> Unit,
    onOpenPoints: () -> Unit,
    onOpenResult: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    val completed = vm.progress.totalCompleted()
    val allDone = vm.isAllDone()
    LabScaffold(
        title = "校园信号测绘",
        subtitle = "课题03 · 校园无线盲区测绘",
        status = if (allDone) "24/24 已完成" else "已完成 $completed/24 次",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MessageBanner(vm.message) { vm.postMessage(null) }

            NextStepCard(
                vm = vm,
                environment = environment,
                onMeasure = onMeasure,
                onOpenResult = onOpenResult,
                onOpenWifiSettings = onOpenWifiSettings,
                onOpenLocationSettings = onOpenLocationSettings,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
            )
            SurveyProgressCard(vm)

            SectionCard(title = "项目说明") {
                Text(
                    text = "在校园 8 个不同遮挡特征点位（走廊 / 转角 / 电梯口 / 楼梯间 / 宿舍金属门 / " +
                        "玻璃窗教室 / 设备机柜实验室 / 室外）各连续测量 3 次 RSSI 与延迟，" +
                        "生成 24 行原始数据、点位照片与邻道扫描明细。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                KeyValueRow("会话 ID", vm.sessionId ?: "未开始（首次测量时创建）")
                KeyValueRow("会话目录", vm.sessionDirName())
                KeyValueRow("已写入", "${vm.rows.size}/24 行")
                KeyValueRow("扫描明细", "${vm.scanFileCount()} 个 scan CSV")
                KeyValueRow("点位照片", "${vm.photoFileCount()} 张")
                KeyValueRow(
                    "延迟协议",
                    "TCP ${vm.settings.tcpTarget} × ${vm.settings.rttAttempts}；HTTP × ${vm.settings.httpAttempts}",
                )
            }

            SectionCard(title = "环境检查") {
                val ssidText = when {
                    !environment.radioReadable -> "定位未开启，无法读取 SSID / RSSI"
                    !environment.wifiConnected -> "未连接 Wi-Fi"
                    else -> "${environment.ssid.ifEmpty { "SSID 未知" }} · RSSI ${environment.rssiDbm?.let { "$it dBm" } ?: "--"}"
                }
                EnvironmentRow(
                    label = "Wi-Fi 连接",
                    ok = environment.wifiConnected,
                    value = ssidText,
                    fixLabel = "打开 Wi-Fi 设置",
                    onFix = onOpenWifiSettings,
                )
                EnvironmentRow(
                    label = "系统定位开关",
                    ok = environment.locationEnabled,
                    value = if (environment.locationEnabled) {
                        "已开启（Android 12+ 读取 RSSI / 扫描结果的前提）"
                    } else {
                        "已关闭：RSSI 与扫描结果将不可读"
                    },
                    fixLabel = "打开定位",
                    onFix = onOpenLocationSettings,
                )
                EnvironmentRow(
                    label = "定位权限",
                    ok = environment.locationPermission,
                    value = "ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION",
                    fixLabel = "去授权",
                    onFix = onRequestPermissions,
                )
                if (environment.nearbyWifiRequired) {
                    EnvironmentRow(
                        label = "附近 Wi-Fi 设备权限",
                        ok = environment.nearbyWifiGranted,
                        value = "Android 13+ NEARBY_WIFI_DEVICES（neverForLocation）",
                        fixLabel = "去授权",
                        onFix = onRequestPermissions,
                    )
                }
                EnvironmentRow(
                    label = "相机权限",
                    ok = environment.cameraPermission,
                    value = "拍照可选；缺失时可跳过，photo_file 留空",
                    fixLabel = "去授权",
                    onFix = onRequestCameraPermission,
                )
                EnvironmentRow(
                    label = "互联网连通性",
                    ok = environment.internetCapable,
                    value = "用于 HTTP TTFB / 总耗时；不可达时相关列留空并记录 note",
                )
                HintText("拒绝定位、关闭定位或未连接 Wi-Fi 时禁止开始测量，且不会写入任何数据。")
                LinkButton(
                    text = "权限被永久拒绝？打开应用设置手动授权",
                    onClick = onOpenAppSettings,
                )
            }

            SectionCard(title = "点位完成度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "总进度 ${vm.progress.totalCompleted()} / 24 次 · ${vm.rows.size} 行已落盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    StatusPill(
                        text = if (allDone) "全部完成" else "进行中",
                        tone = if (allDone) PillTone.Success else PillTone.Info,
                    )
                }
                Spacer(Modifier.height(6.dp))
                vm.points.forEach { point ->
                    val done = vm.repeatsFor(point.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = point.id,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done >= 3) LabColors.Success else LabColors.Primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = point.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = LabColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${point.type.label} · ${point.wallMaterial.label} · 距 AP ${point.distanceM.fmt()} m",
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ProgressSegments(done = done, total = 3, segmentWidth = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "$done/3",
                            style = MaterialTheme.typography.labelSmall,
                            color = LabColors.TextSecondary,
                        )
                    }
                }
            }

            SectionCard(title = "开始测量") {
                val target = vm.nextTarget()
                if (target != null) {
                    PrimaryButton(
                        text = "开始 / 继续测量（${target.point.id} 第 ${target.repeatIndex}/3 次）",
                        onClick = { onMeasure(target.point.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = environment.measurementReady,
                    )
                } else {
                    PrimaryButton(
                        text = "全部完成 · 查看结果",
                        onClick = onOpenResult,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!environment.measurementReady) {
                    Spacer(Modifier.height(6.dp))
                    HintText("环境检查未通过，请先修复上方红色项：${environment.blockingSummary}")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "结果",
                        onClick = onOpenResult,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "点位",
                        onClick = onOpenPoints,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "设置",
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
@Composable
private fun NextStepCard(
    vm: SurveyViewModel,
    environment: EnvironmentStatus,
    onMeasure: (String) -> Unit,
    onOpenResult: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val completed = vm.progress.totalCompleted()
    val allDone = vm.isAllDone()
    val envReady = environment.radioReadable && environment.wifiConnected && environment.locationEnabled &&
        environment.locationPermission && (!environment.nearbyWifiRequired || environment.nearbyWifiGranted)
    val target = vm.nextTarget()
    val targetPoint = target?.point
    SectionCard(title = "下一步", accent = LabColors.Primary) {
        when {
            !envReady -> {
                StatusPill("步骤 1 / 3 · 环境检查", PillTone.Warning)
                Spacer(Modifier.height(8.dp))
                Text("先修复环境，再开始测量", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("需要同时满足：Wi-Fi 已连接、系统定位开关打开、定位权限已授予（Android 12+ 读 RSSI 的前提）。")
                Spacer(Modifier.height(10.dp))
                val fix = when {
                    !environment.wifiConnected -> onOpenWifiSettings
                    !environment.locationEnabled -> onOpenLocationSettings
                    !environment.locationPermission -> onRequestPermissions
                    environment.nearbyWifiRequired && !environment.nearbyWifiGranted -> onRequestPermissions
                    else -> onOpenAppSettings
                }
                PrimaryButton("去修复环境", fix, Modifier.fillMaxWidth())
            }
            allDone -> {
                StatusPill("步骤 3 / 3 · 导出结果", PillTone.Success)
                Spacer(Modifier.height(8.dp))
                Text("24 次测量已完成", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("到结果页检查 24 行数据与点位照片，生成并保存 ZIP 提交；照片也可在结果页直接点开查看。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("查看结果并导出 ZIP", onOpenResult, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("查看点位照片", onOpenResult, Modifier.fillMaxWidth())
            }
            target != null -> {
                StatusPill("步骤 2 / 3 · 逐点测量", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "继续测量：" + (targetPoint?.id ?: "") + " " + (targetPoint?.name ?: "") + " 第 " + target.repeatIndex + "/" + DataContract.REPEATS_PER_POINT + " 次",
                    style = MaterialTheme.typography.titleMedium,
                    color = LabColors.TextPrimary,
                )
                HintText("已写入 " + vm.rows.size + "/" + DataContract.TOTAL_ROWS + " 行；同一时段测完 8 个点位，点位站位与朝向保持固定。")
                if (targetPoint != null && targetPoint.photoFile.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    StatusPill("本点位还没有拍照（建议先拍墙体/AP 方向）", PillTone.Warning)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("开始本次测量", { onMeasure(target.point.id) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("查看已测结果", onOpenResult, Modifier.fillMaxWidth())
            }
            else -> {
                StatusPill("步骤 2 / 3 · 逐点测量", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text("继续完成剩余点位", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("已写入 " + vm.rows.size + "/" + DataContract.TOTAL_ROWS + " 行；点「开始本次测量」后会按进度自动选点。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("打开点位列表", { }, Modifier.fillMaxWidth(), enabled = false)
            }
        }
    }
}

@Composable
private fun SurveyProgressCard(vm: SurveyViewModel) {
    val completed = vm.progress.totalCompleted()
    SectionCard(title = "测绘进度") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("总进度", style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(completed.toString() + " / " + DataContract.TOTAL_ROWS, style = MaterialTheme.typography.labelMedium, color = LabColors.TextSecondary)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { (completed.toFloat() / DataContract.TOTAL_ROWS).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (completed >= DataContract.TOTAL_ROWS) LabColors.Success else LabColors.Primary,
            trackColor = LabColors.SurfaceHigh,
        )
        Spacer(Modifier.height(10.dp))
        vm.points.forEach { point ->
            val repeats = vm.progress.repeats(point.id)
            val hasPhoto = point.photoFile.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(point.id + " " + point.name, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (hasPhoto) StatusPill("照片", PillTone.Success) else StatusPill("未拍照", PillTone.Muted)
                Spacer(Modifier.width(6.dp))
                Text(repeats.toString() + "/" + DataContract.REPEATS_PER_POINT, style = MaterialTheme.typography.labelSmall, color = if (repeats >= DataContract.REPEATS_PER_POINT) LabColors.Success else LabColors.TextSecondary)
            }
        }
    }
}
