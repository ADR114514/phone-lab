package com.mobilelab.vibrodiag.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.permission.PermissionRequester
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.RecordingBadge
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.core.ui.ChartLegend
import com.mobilelab.core.ui.LineChart
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.RecordingSaveState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.model.Conditions
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class RecordPhase { Idle, Counting, Recording }

@Composable
fun RecordScreen(state: AppState, permission: PermissionRequester) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var conditionId by rememberSaveable { mutableStateOf(state.initialCondition()) }
    var groupIndex by rememberSaveable { mutableIntStateOf(state.nextGroup(state.initialCondition()) ?: 0) }
    var phase by remember { mutableStateOf(RecordPhase.Idle) }
    var countdown by remember { mutableIntStateOf(3) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var rmsDbfs by remember { mutableDoubleStateOf(-120.0) }
    var peakDbfs by remember { mutableDoubleStateOf(-120.0) }
    var accelMag by remember { mutableDoubleStateOf(0.0) }
    var waveform by remember { mutableStateOf(List(180) { 0f }) }
    var showInterruptDialog by remember { mutableStateOf(false) }

    val saveState = state.saveState
    val saved = saveState as? RecordingSaveState.Saved
    val failed = saveState as? RecordingSaveState.Failed
    val saving = saveState is RecordingSaveState.Saving
    val recording = phase == RecordPhase.Recording
    val currentPhase by rememberUpdatedState(phase)

    // Clear a stale "saved" card when entering the screen again.
    LaunchedEffect(Unit) {
        if (state.activeRecording == null &&
            (saveState is RecordingSaveState.Saved || saveState is RecordingSaveState.Failed)
        ) {
            state.resetRecordingState()
        }
    }

    // Keep the screen on while the 3-2-1 countdown or the acquisition runs.
    DisposableEffect(phase) {
        val keepOn = phase == RecordPhase.Counting || phase == RecordPhase.Recording
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Countdown then start the audio + accel capture.
    LaunchedEffect(phase) {
        if (phase == RecordPhase.Counting) {
            for (k in 3 downTo 1) {
                countdown = k
                delay(1000L)
            }
            val error = state.startRecording(conditionId, groupIndex)
            phase = if (error == null) RecordPhase.Recording else RecordPhase.Idle
        }
    }

    // Live values while recording; finalises as soon as the engine stops itself.
    LaunchedEffect(phase) {
        if (phase == RecordPhase.Recording) {
            while (state.recorder.isRecording()) {
                elapsed = state.recorder.elapsedSeconds()
                rmsDbfs = state.recorder.liveRmsDbfs()
                peakDbfs = state.recorder.livePeakDbfs()
                accelMag = state.recorder.latestAccelMagnitudeG()
                waveform = state.recorder.waveform(180)
                delay(80L)
            }
            phase = RecordPhase.Idle
            state.finishRecording(false)
        }
    }

    // Interruption safety: going to background flushes the WAV and saves a partial sample.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            when (currentPhase) {
                RecordPhase.Recording -> state.finishRecording(true)
                // Never start a capture from the background: cancel the countdown.
                RecordPhase.Counting -> phase = RecordPhase.Idle
                RecordPhase.Idle -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = phase == RecordPhase.Counting || recording) {
        showInterruptDialog = true
    }

    LabScaffold(
        title = "开始采集",
        subtitle = "每组 " + state.settings.durationS + " 秒 · 5 工况 × 15 组",
        status = if (state.activeRecording != null) "录音中" else "第 " + (groupIndex.coerceAtLeast(1)) + " 组",
        onBack = {
            if (recording || phase == RecordPhase.Counting) showInterruptDialog = true else state.screen = Screen.Home
        },
    ) {
        ScrollColumn {
            ConditionCard(
                conditionId = conditionId,
                groupIndex = groupIndex,
                enabled = !recording && !saving && phase == RecordPhase.Idle && saved == null,
                onSelect = { index ->
                    conditionId = Conditions.all[index].id
                    groupIndex = state.nextGroup(conditionId) ?: 0
                },
            )
            CaptureTipsCard(state)

            if (saved != null && saved.sample != null) {
                SavedResultCard(
                    state = state,
                    saveState = saved,
                    onRedo = {
                        val sample = saved.sample
                        state.deleteSample(sample)
                        state.resetRecordingState()
                        groupIndex = sample.groupIndex
                    },
                    onNext = {
                        state.resetRecordingState()
                        groupIndex = state.nextGroup(conditionId) ?: 0
                    },
                )
            } else if (failed != null) {
                SectionCard(title = "保存失败", accent = LabColors.Error) {
                    Text(failed.message, style = MaterialTheme.typography.bodySmall, color = LabColors.Error)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("重新采集本组", {
                        state.resetRecordingState()
                        groupIndex = state.nextGroup(conditionId) ?: groupIndex
                    }, Modifier.fillMaxWidth())
                }
            } else if (saving) {
                SectionCard(title = "保存与特征提取") {
                    Text(
                        "正在写入 WAV / 加速度 CSV、提取 32 维特征并更新 dataset_index…",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = LabColors.Primary,
                        trackColor = LabColors.SurfaceHigh,
                    )
                }
            } else if (recording) {
                RecordingCard(
                    elapsed = elapsed,
                    durationS = state.settings.durationS,
                    rmsDbfs = rmsDbfs,
                    peakDbfs = peakDbfs,
                    accelMag = accelMag,
                    waveform = waveform,
                    audioSource = state.recorder.audioSource(),
                )
                SecondaryButton("提前结束并保存", { state.finishRecording(true) }, Modifier.fillMaxWidth())
            } else {
                RecordingIdleCard(
                    permission = permission,
                    groupIndex = groupIndex,
                    conditionId = conditionId,
                    durationS = state.settings.durationS,
                    onStart = {
                        if (!permission.allGranted) {
                            LabLog.w("RecordScreen", "RECORD_AUDIO not granted, requesting")
                            permission.request()
                            state.showMessage("请先授予麦克风权限，再开始采集")
                            return@RecordingIdleCard
                        }
                        val next = state.nextGroup(conditionId)
                        if (next == null) {
                            state.showMessage("本工况 15 组已完成，可在数据集删除后重录")
                        } else {
                            groupIndex = next
                            state.resetRecordingState()
                            phase = RecordPhase.Counting
                        }
                    },
                )
            }
        }
    }

    if (phase == RecordPhase.Counting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = LabColors.Surface,
            title = { Text("准备录制", color = LabColors.TextPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = countdown.toString(),
                        fontSize = 72.sp,
                        color = LabColors.Primary,
                    )
                    HintText("3-2-1 后自动开始录音，请保持手机与风扇位置不动")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInterruptDialog = true }) {
                    Text("取消", color = LabColors.TextSecondary)
                }
            },
        )
    }

    if (showInterruptDialog) {
        ConfirmDialog(
            title = if (recording || phase == RecordPhase.Counting) "中断录制" else "结束采集",
            text = if (recording) {
                "正在录音，确定中断并保存当前已采集的部分吗？不完整样本会标记为 interrupted 且不参与训练。"
            } else {
                "确定中止倒计时并返回首页？"
            },
            confirmLabel = if (recording) "中断并保存" else "返回首页",
            danger = true,
            onConfirm = {
                showInterruptDialog = false
                when {
                    recording -> {
                        state.finishRecording(true)
                        state.screen = Screen.Home
                    }
                    phase == RecordPhase.Counting -> {
                        phase = RecordPhase.Idle
                        state.screen = Screen.Home
                    }
                    else -> state.screen = Screen.Home
                }
            },
            onDismiss = { showInterruptDialog = false },
        )
    }
}

@Composable
private fun ConditionCard(
    conditionId: String,
    groupIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    SectionCard(title = "工况选择") {
        ChipRow(
            options = Conditions.all.map { it.shortLabel },
            selectedIndex = Conditions.requireIndex(conditionId),
            onSelect = onSelect,
            enabled = enabled,
        )
        Spacer(Modifier.height(10.dp))
        val condition = Conditions.byId(conditionId)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(condition?.label ?: conditionId, PillTone.Info)
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = "g" + String.format(Locale.US, "%02d", groupIndex.coerceAtLeast(1)) + " / " + Conditions.TARGET_GROUPS,
                tone = PillTone.Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        HintText(condition?.setup ?: "")
    }
}

@Composable
private fun CaptureTipsCard(state: AppState) {
    SectionCard(title = "采集前准备") {
        HintText("1. 手机平放/固定在风扇底座正前方约 30 cm 的桌面，麦克风朝向扇叶。")
        HintText("2. 关闭空调与其他电器，保持环境安静；每工况之间保持相同摆放。")
        HintText("3. 录音开始后 15 秒内不要触碰手机，保持屏幕常亮。")
        HintText("4. 当前加速度模式：" + state.settings.accelMode.label + "（" + state.accelCapability.sensorName + "）。")
    }
}

@Composable
private fun RecordingIdleCard(
    permission: PermissionRequester,
    groupIndex: Int,
    conditionId: String,
    durationS: Int,
    onStart: () -> Unit,
) {
    SectionCard(title = "本组采集") {
        if (!permission.allGranted) {
            Text(
                "需要 RECORD_AUDIO 权限才能录音。",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Tertiary,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("授予麦克风权限", { permission.request() }, Modifier.fillMaxWidth())
        } else {
            Text(
                "将采集 " + durationS + " 秒 44.1 kHz 单声道音频与加速度数据。",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "开始录制（3-2-1 倒计时）",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = groupIndex > 0,
            )
            Spacer(Modifier.height(6.dp))
            HintText(
                if (groupIndex > 0) "当前等待采集：" + Conditions.labelOf(conditionId) + " 第 " + groupIndex + " 组"
                else "本工况 15 组已完成，可到数据集页删除后重录",
            )
        }
    }
}

@Composable
private fun RecordingCard(
    elapsed: Double,
    durationS: Int,
    rmsDbfs: Double,
    peakDbfs: Double,
    accelMag: Double,
    waveform: List<Float>,
    audioSource: String,
) {
    SectionCard(title = "正在录音") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingBadge(
                elapsed = String.format(Locale.US, "%05.2f", elapsed) + " / " + durationS + " s",
                active = true,
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(audioSource, PillTone.Info)
        }
        Spacer(Modifier.height(10.dp))
        LineChart(
            values = waveform,
            modifier = Modifier.fillMaxWidth(),
            color = LabColors.Primary,
            height = 120.dp,
            minValue = -1f,
            maxValue = 1f,
            showDots = false,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("音频 RMS", String.format(Locale.US, "%.1f", rmsDbfs), "dBFS", modifier = Modifier.weight(1f))
            MetricTile("音频峰值", String.format(Locale.US, "%.1f", peakDbfs), "dBFS", modifier = Modifier.weight(1f))
            MetricTile("加速度幅值", String.format(Locale.US, "%.3f", accelMag), "g", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        HintText("录音中请勿移动手机；结束会自动提取特征。")
    }
}

@Composable
private fun SavedResultCard(
    state: AppState,
    saveState: RecordingSaveState.Saved,
    onRedo: () -> Unit,
    onNext: () -> Unit,
) {
    val sample = saveState.sample ?: return
    val features = sample.features
    SectionCard(
        title = "本组特征摘要",
        accent = if (saveState.interrupted) LabColors.Tertiary else LabColors.Success,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(sample.paths.stem, PillTone.Success)
            Spacer(Modifier.width(8.dp))
            if (saveState.interrupted) {
                StatusPill("已中断（不参与训练）", PillTone.Warning)
            } else {
                StatusPill("已保存", PillTone.Success)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("时长", Fmt.value(sample.durationS, 1), "s", modifier = Modifier.weight(1f))
            MetricTile("音频 RMS", Fmt.value(features["audio_rms_dbfs"] ?: Double.NaN, 1), "dBFS", modifier = Modifier.weight(1f))
            MetricTile("峰值频率", Fmt.value(features["audio_peak_hz"] ?: Double.NaN, 0), "Hz", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("1k–2k 占比", Fmt.value((features["band_1k_2k"] ?: Double.NaN) * 100.0, 1), "%", modifier = Modifier.weight(1f))
            MetricTile("振动 RMS", Fmt.value(features["accel_rms"] ?: Double.NaN, 3), "m/s²", modifier = Modifier.weight(1f))
            MetricTile("振动峰值频率", Fmt.value(features["accel_peak_hz"] ?: Double.NaN, 1), "Hz", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        HintText("音频采样率 44.1 kHz · " + sample.features.size + " 维特征已写入 " + sample.paths.features.name)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("重录本组", onRedo, Modifier.weight(1f))
            PrimaryButton("下一组", onNext, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("返回首页", { state.screen = Screen.Home }, Modifier.fillMaxWidth())
    }
}