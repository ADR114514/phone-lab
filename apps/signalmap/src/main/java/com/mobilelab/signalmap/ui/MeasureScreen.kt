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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.EmptyState
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LineChart
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.service.EnvironmentProbe
import com.mobilelab.signalmap.service.EnvironmentStatus
import com.mobilelab.signalmap.service.MeasurementStep
import com.mobilelab.signalmap.vm.MeasurementUi
import com.mobilelab.signalmap.vm.SurveyViewModel
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun MeasureScreen(
    vm: SurveyViewModel,
    pointId: String,
    environment: EnvironmentStatus,
    onBack: () -> Unit,
    onOpenResult: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    val point = vm.pointById(pointId)
    if (point == null) {
        LabScaffold(title = "点位不存在", onBack = onBack) {
            EmptyState("找不到点位 $pointId", "请返回点位列表重新选择")
        }
        return
    }

    // 1 Hz ticker for the 30 s inter-repeat countdown.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pointId) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val remainingMs = remember(nowMs, vm.progress, pointId) { vm.repeatGateRemainingMs(pointId) }

    val done = vm.repeatsFor(pointId)
    val nextRepeat = vm.nextRepeatIndex(pointId)
    val measurement = vm.measurement
    val running = measurement as? MeasurementUi.Running
    val doneRow = if (measurement is MeasurementUi.Done) {
        vm.rows.lastOrNull { it.pointId == measurement.pointId && it.repeatIndex == measurement.repeatIndex }
    } else {
        null
    }
    val cameraAvailable = EnvironmentProbe.isCameraFeatureAvailable(vm.getApplication())
    val photoFile: File? = vm.photoFile(point.photoFile)

    LabScaffold(
        title = "${point.id} ${point.name}",
        subtitle = "${point.type.label} · ${point.wallMaterial.label} · 已完成 $done/3 次",
        onBack = onBack,
        actions = {
            StatusPill(
                text = "$done/3",
                tone = if (done >= 3) PillTone.Success else PillTone.Info,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MessageBanner(vm.message) { vm.postMessage(null) }

            // Station quick switcher (keeps the 8 station workflow on one screen).
            val ids = vm.points.map { it.id }
            ChipSelector(
                options = ids,
                selectedIndex = ids.indexOf(pointId),
                onSelect = { index -> vm.switchMeasurePoint(ids[index]) },
            )

            SectionCard(title = "点位信息") {
                KeyValueRow("点位类型", "${point.type.label}（${point.type.id}）")
                KeyValueRow("墙体材质", "${point.wallMaterial.label}（${point.wallMaterial.id}）")
                KeyValueRow("估计距离", point.distanceM?.let { "${it.fmt()} m" } ?: "--")
                KeyValueRow("楼层", point.floor?.toString() ?: "--")
                KeyValueRow("重复次数", "$done / 3")
                LabeledValue("遮挡描述", point.obstruction)
            }

            CameraCaptureCard(
                pointId = pointId,
                photoRelativePath = point.photoFile,
                photoFile = photoFile,
                cameraGranted = environment.cameraPermission,
                cameraFeatureAvailable = cameraAvailable,
                interactionEnabled = running == null,
                onRequestPermission = onRequestCameraPermission,
                onNewTarget = { vm.newPhotoTarget(pointId) },
                onCaptured = { target -> vm.onPhotoCaptured(pointId, target) },
                onClearPhoto = { vm.clearPhoto(pointId) },
                onError = { error -> vm.postMessage(error) },
            )

            EnvironmentBlockerCard(
                status = environment,
                onRequestPermissions = onRequestPermissions,
                onOpenLocationSettings = onOpenLocationSettings,
                onOpenAppSettings = onOpenAppSettings,
                onOpenWifiSettings = onOpenWifiSettings,
            )

            SectionCard(title = "本次测量") {
                val steps = buildList {
                    add(MeasurementStep.CONNECTION)
                    add(MeasurementStep.SCAN)
                    add(MeasurementStep.GATEWAY)
                    add(MeasurementStep.INTERNET)
                    add(MeasurementStep.HTTP)
                    if (vm.settings.throughputEnabled) add(MeasurementStep.THROUGHPUT)
                    add(MeasurementStep.WRITE)
                }
                val currentIndex = if (running != null) steps.indexOf(running.step) else -1
                val finished = measurement is MeasurementUi.Done
                steps.forEachIndexed { index, step ->
                    val state = when {
                        finished -> StepState.Done
                        currentIndex < 0 -> StepState.Pending
                        index < currentIndex -> StepState.Done
                        index == currentIndex -> StepState.Active
                        else -> StepState.Pending
                    }
                    StepProgressRow(
                        index = index + 1,
                        title = step.label,
                        detail = step.detail,
                        state = state,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        label = "RSSI",
                        value = (running?.live?.rssiDbm ?: doneRow?.rssiDbm ?: environment.rssiDbm).fmt(),
                        unit = "dBm",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "平均延迟",
                        value = (running?.live?.avgLatencyMs ?: doneRow?.latencyAvgMs).fmt(),
                        unit = "ms",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "丢包率",
                        value = (running?.live?.lossPct ?: doneRow?.lossPct).fmt(),
                        unit = "%",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "首字节",
                        value = (running?.live?.httpTtfbMs ?: doneRow?.httpTtfbMs).fmt(),
                        unit = "ms",
                        modifier = Modifier.weight(1f),
                    )
                }

                val series = running?.rttSeries.orEmpty()
                if (series.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "实时 TCP RTT",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    LineChart(values = series, height = 120.dp)
                }

                val liveMessage = running?.live?.message.orEmpty()
                if (liveMessage.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HintText(liveMessage)
                }

                Spacer(Modifier.height(10.dp))
                when {
                    running != null -> {
                        PrimaryButton(
                            text = "测量中…",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                            loading = true,
                        )
                    }

                    nextRepeat == null -> {
                        val nextPoint = vm.points.firstOrNull { vm.repeatsFor(it.id) < 3 }
                        PrimaryButton(
                            text = "本点 3 次已完成",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (nextPoint != null) {
                            PrimaryButton(
                                text = "下一点位：${nextPoint.id} ${nextPoint.name}",
                                onClick = { vm.switchMeasurePoint(nextPoint.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            PrimaryButton(
                                text = "全部点位已完成 · 查看结果",
                                onClick = onOpenResult,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    else -> {
                        PrimaryButton(
                            text = "开始本点第 $nextRepeat/3 次测量",
                            onClick = { vm.startMeasurement(pointId) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = environment.measurementReady && remainingMs == 0L,
                        )
                        if (remainingMs > 0L) {
                            Spacer(Modifier.height(6.dp))
                            HintText("同一位置两次测量间隔需 ≥30 秒：请再等待 ${remainingMs / 1000 + 1} 秒")
                        }
                        if (!environment.measurementReady) {
                            Spacer(Modifier.height(6.dp))
                            HintText("环境不满足，已禁止开始：${environment.blockingSummary}")
                        }
                    }
                }
            }

            when (measurement) {
                is MeasurementUi.Done -> SectionCard(
                    title = "第 ${measurement.repeatIndex}/3 次测量完成",
                    accent = LabColors.Success,
                ) {
                    KeyValueRow("写入文件", "survey_${vm.sessionId ?: "--"}.csv")
                    KeyValueRow("写入行号", "第 ${vm.rows.size} 行 / 24")
                    KeyValueRow("照片", doneRow?.photoFile?.ifEmpty { "未拍照" } ?: "未拍照")
                    if (measurement.note.isNotEmpty()) {
                        LabeledValue("异常 / 环境备注", measurement.note)
                    }
                    Spacer(Modifier.height(6.dp))
                    val nextPoint = vm.points.firstOrNull { vm.repeatsFor(it.id) < 3 }
                    if (vm.repeatsFor(pointId) >= 3) {
                        if (nextPoint != null) {
                            SecondaryButton(
                                text = "前往下一点位 ${nextPoint.id}",
                                onClick = { vm.switchMeasurePoint(nextPoint.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            SecondaryButton(
                                text = "查看结果并导出",
                                onClick = onOpenResult,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        HintText("本点还需 ${3 - vm.repeatsFor(pointId)} 次；两次测量间隔至少 30 秒。")
                    }
                }

                is MeasurementUi.Failed -> SectionCard(
                    title = "本次测量未完成",
                    accent = LabColors.Error,
                ) {
                    Text(
                        text = measurement.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText("未写入任何数据。修复环境后重新开始即可。")
                }

                else -> Unit
            }

            SectionCard(title = "会话") {
                KeyValueRow("会话 ID", vm.sessionId ?: "首次测量时创建")
                KeyValueRow("目录", vm.sessionDirName())
                KeyValueRow("已记录行数", "${vm.rows.size} / 24")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "结果页",
                        onClick = onOpenResult,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}