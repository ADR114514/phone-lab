package com.mobilelab.vibrodiag.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.permission.PermissionRequester
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.RecordingBadge
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.RecordingSaveState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.data.BlindSession
import com.mobilelab.vibrodiag.model.Conditions
import kotlinx.coroutines.delay
import java.util.Locale

private enum class BlindPhase { Idle, Counting, Recording }

@Composable
fun BlindTestScreen(state: AppState, permission: PermissionRequester) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var phase by remember { mutableStateOf(BlindPhase.Idle) }
    var countdown by remember { mutableIntStateOf(3) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var rmsDbfs by remember { mutableDoubleStateOf(-120.0) }
    var accelMag by remember { mutableDoubleStateOf(0.0) }
    var waveform by remember { mutableStateOf(List(160) { 0f }) }
    var showSetupHint by remember { mutableStateOf(false) }
    var showInterruptDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val session = state.blindSession
    val trial = state.blindTrial
    val results = state.blindResults
    val saveState = state.saveState
    val saving = saveState is RecordingSaveState.Saving
    val failed = saveState as? RecordingSaveState.Failed
    val recording = phase == BlindPhase.Recording
    val complete = state.blindIsComplete()
    val summaryVisible = complete && trial == null
    val currentPhase by rememberUpdatedState(phase)

    DisposableEffect(phase) {
        val keepOn = phase == BlindPhase.Counting || phase == BlindPhase.Recording
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(phase) {
        if (phase == BlindPhase.Counting) {
            for (k in 3 downTo 1) {
                countdown = k
                delay(1000L)
            }
            val current = state.blindTrial
            if (current == null || session == null) {
                phase = BlindPhase.Idle
                return@LaunchedEffect
            }
            val error = state.startRecording(
                conditionId = current.conditionId,
                groupIndex = current.trialIndex,
                blindTrialIndex = current.trialIndex,
            )
            phase = if (error == null) BlindPhase.Recording else BlindPhase.Idle
        }
    }

    LaunchedEffect(phase) {
        if (phase == BlindPhase.Recording) {
            while (state.recorder.isRecording()) {
                elapsed = state.recorder.elapsedSeconds()
                rmsDbfs = state.recorder.liveRmsDbfs()
                accelMag = state.recorder.latestAccelMagnitudeG()
                waveform = state.recorder.waveform(160)
                delay(80L)
            }
            phase = BlindPhase.Idle
            state.finishRecording(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            when (currentPhase) {
                BlindPhase.Recording -> state.finishRecording(true)
                BlindPhase.Counting -> phase = BlindPhase.Idle
                BlindPhase.Idle -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = recording || phase == BlindPhase.Counting) {
        showInterruptDialog = true
    }

    LabScaffold(
        title = "盲测",
        subtitle = "5 工况 × 3 组 = 15 组 · 预测后再揭示标签",
        status = if (complete) "盲测完成" else "已测 " + results.size + " / " + (session?.total ?: BlindSession.TRIALS_PER_CONDITION * Conditions.all.size),
        onBack = {
            if (recording || phase == BlindPhase.Counting) showInterruptDialog = true
            else if (state.screen != Screen.Home) state.screen = Screen.Home
        },
    ) {
        ScrollColumn {
            if (session == null) {
                BlindIntroCard(
                    sampleCount = state.usableSamples().size,
                    onStart = {
                        val error = state.startBlindTest()
                        if (error != null) state.showMessage(error)
                    },
                )
            } else if (summaryVisible) {
                BlindSummaryCard(
                    state = state,
                    onReset = { showResetDialog = true },
                )
            } else {
                BlindProgressCard(state, results.size, session.total)
                if (trial == null) {
                    SectionCard(title = "准备盲测样本") {
                        PrimaryButton("准备下一组", { state.nextBlindTrial() }, Modifier.fillMaxWidth())
                    }
                } else {
                    when {
                        trial.sample == null -> {
                            BlindSetupCard(
                                trialIndex = trial.trialIndex,
                                total = session.total,
                                conditionId = trial.conditionId,
                                permission = permission,
                                saving = saving,
                                recording = recording,
                                elapsed = elapsed,
                                rmsDbfs = rmsDbfs,
                                accelMag = accelMag,
                                waveform = waveform,
                                errorText = failed?.message,
                                onShowSetup = { showSetupHint = true },
                                onStart = {
                                    if (!permission.allGranted) {
                                        LabLog.w("BlindTestScreen", "RECORD_AUDIO not granted, requesting")
                                        permission.request()
                                        state.showMessage("请先授予麦克风权限，再录制盲测样本")
                                        return@BlindSetupCard
                                    }
                                    phase = BlindPhase.Counting
                                },
                            )
                        }
                        else -> {
                            BlindPredictionCard(
                                state = state,
                                trialIndex = trial.trialIndex,
                                total = session.total,
                                onNext = { state.nextBlindTrial() },
                            )
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    BlindRunningAccuracyCard(state)
                }
            }
        }
    }

    if (phase == BlindPhase.Counting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = LabColors.Surface,
            title = { Text("盲测采集倒计时", color = LabColors.TextPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(countdown.toString(), fontSize = 72.sp, color = LabColors.Primary)
                    HintText("3-2-1 后自动开始，请保持设备不动")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInterruptDialog = true }) {
                    Text("取消", color = LabColors.TextSecondary)
                }
            },
        )
    }

    if (showSetupHint && trial != null) {
        AlertDialog(
            onDismissRequest = { showSetupHint = false },
            containerColor = LabColors.Surface,
            title = { Text("布置者查看（仅设置设备用）", color = LabColors.Tertiary) },
            text = {
                Column {
                    Text(
                        text = Conditions.labelOf(trial.conditionId),
                        style = MaterialTheme.typography.titleLarge,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText("请按该工况设置电风扇/吹风机后关闭此窗口；预测阶段不会再显示标签。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSetupHint = false }) { Text("知道了", color = LabColors.Primary) }
            },
        )
    }

    if (showInterruptDialog) {
        ConfirmDialog(
            title = "中断盲测采集",
            text = "正在录音，确定中断并返回首页吗？当前盲测样本会保存为不完整样本，建议重新开始盲测。",
            confirmLabel = "中断返回",
            danger = true,
            onConfirm = {
                showInterruptDialog = false
                if (recording || phase == BlindPhase.Counting) {
                    if (recording) state.finishRecording(true)
                    phase = BlindPhase.Idle
                    state.screen = Screen.Home
                }
            },
            onDismiss = { showInterruptDialog = false },
        )
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = "重置盲测",
            text = "将删除本会话盲测录音与 blind_test CSV，重新生成 15 组盲测顺序。确定重置？",
            confirmLabel = "重置",
            danger = true,
            onConfirm = {
                showResetDialog = false
                state.resetBlindTest()
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

@Composable
private fun BlindIntroCard(sampleCount: Int, onStart: () -> Unit) {
    SectionCard(title = "盲测规则") {
        HintText("· 每个工况抽 3 组，共 15 组；组顺序由 App 随机打乱。")
        HintText("· 采集过程中不显示真实工况标签，只显示“样本 #k”。")
        HintText("· 录完一组先点“预测”，看到模型结果后再点“揭示真实标签”。")
        HintText("· 训练集使用采集页的全部已标注样本，盲测样本不参与训练。")
        HintText("· 15 组结束后统计准确率并写入 blind_test CSV。")
        Spacer(Modifier.height(10.dp))
        StatusPill(
            text = if (sampleCount < 3) "训练集样本 " + sampleCount + " 组（建议先完成采集）" else "训练集样本 " + sampleCount + " 组",
            tone = if (sampleCount < 3) PillTone.Warning else PillTone.Success,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("开始盲测（随机 15 组）", onStart, Modifier.fillMaxWidth(), enabled = sampleCount >= 3)
        Spacer(Modifier.height(6.dp))
        HintText("建议在完成 5×15 组采集后再进行盲测，以保证训练集质量。")
    }
}

@Composable
private fun BlindProgressCard(state: AppState, done: Int, total: Int) {
    SectionCard(title = "盲测进度") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已完成 " + done + " / " + total + " 组",
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusPill("准确率 " + Fmt.percent(state.blindAccuracy()), PillTone.Info)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = LabColors.Secondary,
            trackColor = LabColors.SurfaceHigh,
        )
        Spacer(Modifier.height(6.dp))
        HintText("盲测样本写入 " + state.repository.blindDir.name + "/ 目录，CSV 在完成每组后即时更新。")
    }
}

@Composable
private fun BlindSetupCard(
    trialIndex: Int,
    total: Int,
    conditionId: String,
    permission: PermissionRequester,
    saving: Boolean,
    recording: Boolean,
    elapsed: Double,
    rmsDbfs: Double,
    accelMag: Double,
    waveform: List<Float>,
    errorText: String?,
    onShowSetup: () -> Unit,
    onStart: () -> Unit,
) {
    SectionCard(title = "样本 #" + (trialIndex + 1) + " / " + total) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill("真实标签已隐藏", PillTone.Warning)
            Spacer(Modifier.weight(1f))
            LinkButton("布置者查看工况", onShowSetup)
        }
        Spacer(Modifier.height(8.dp))
        if (saving) {
            Text("正在保存盲测样本并提取特征…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = LabColors.Primary,
                trackColor = LabColors.SurfaceHigh,
            )
        } else if (recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingBadge(String.format(Locale.US, "%05.2f", elapsed) + " s", true)
                Spacer(Modifier.width(8.dp))
                StatusPill("录音中", PillTone.Error)
            }
            Spacer(Modifier.height(8.dp))
            com.mobilelab.core.ui.LineChart(
                values = waveform,
                modifier = Modifier.fillMaxWidth(),
                color = LabColors.Primary,
                height = 110.dp,
                minValue = -1f,
                maxValue = 1f,
                showDots = false,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("音频 RMS", String.format(Locale.US, "%.1f", rmsDbfs), "dBFS", modifier = Modifier.weight(1f))
                MetricTile("加速度", String.format(Locale.US, "%.3f", accelMag), "g", modifier = Modifier.weight(1f))
            }
        } else {
            Text(
                "请先按布置者提示设置工况，然后录制本组。录音过程中界面不显示真实标签。",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            if (errorText != null) {
                Spacer(Modifier.height(6.dp))
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = LabColors.Error)
            }
            Spacer(Modifier.height(10.dp))
            if (!permission.allGranted) {
                PrimaryButton("授予麦克风权限", { permission.request() }, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("录制本盲测样本", onStart, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BlindPredictionCard(state: AppState, trialIndex: Int, total: Int, onNext: () -> Unit) {
    val trial = state.blindTrial ?: return
    val sample = trial.sample ?: return
    SectionCard(title = "样本 #" + (trialIndex + 1) + " / " + total + " · 预测") {
        StatusPill("真实标签仍隐藏", PillTone.Warning)
        Spacer(Modifier.height(10.dp))
        HintText("已采集 " + Fmt.value(sample.durationS, 1) + " s，特征文件 " + sample.paths.features.name)
        Spacer(Modifier.height(10.dp))
        val prediction = trial.prediction
        if (prediction == null) {
            PrimaryButton("预测", { state.predictBlindTrial() }, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetricTile(
                    label = "预测结果",
                    value = Conditions.labelOf(prediction.label),
                    modifier = Modifier.weight(1f),
                    accent = LabColors.Secondary,
                )
                Spacer(Modifier.width(10.dp))
                MetricTile(
                    label = "置信度",
                    value = confidencePercent(prediction.confidence),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            HintText("近邻类别：" + prediction.neighborLabels.joinToString(" / ") { Conditions.shortLabelOf(it) })
            Spacer(Modifier.height(10.dp))
            if (!trial.revealed) {
                PrimaryButton("揭示真实标签", { state.revealBlindTrial() }, Modifier.fillMaxWidth())
            } else {
                val truth = trial.conditionId
                val correct = prediction.label == truth
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetricTile(
                        label = "真实标签",
                        value = Conditions.labelOf(truth),
                        modifier = Modifier.weight(1f),
                        accent = LabColors.Tertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    MetricTile(
                        label = "判定",
                        value = if (correct) "正确" else "错误",
                        modifier = Modifier.weight(1f),
                        accent = if (correct) LabColors.Success else LabColors.Error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val isLast = state.blindResults.size >= (state.blindSession?.total ?: 0)
                PrimaryButton(if (isLast) "查看盲测结果" else "下一组", onNext, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BlindRunningAccuracyCard(state: AppState) {
    SectionCard(title = "累计准确率") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "准确率",
                value = Fmt.value(state.blindAccuracy() * 100.0, 1),
                unit = "%",
                modifier = Modifier.weight(1f),
                accent = LabColors.Success,
            )
            MetricTile(
                label = "正确 / 总数",
                value = state.blindResults.count { it.correct }.toString() + " / " + state.blindResults.size,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BlindSummaryCard(state: AppState, onReset: () -> Unit) {
    val session = state.blindSession
    val total = session?.total ?: 0
    val correct = state.blindResults.count { it.correct }
    SectionCard(title = "盲测完成", accent = LabColors.Success) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "盲测准确率",
                value = Fmt.value(state.blindAccuracy() * 100.0, 1),
                unit = "%",
                modifier = Modifier.weight(1f),
                accent = LabColors.Success,
            )
            MetricTile(
                label = "正确 / 总数",
                value = correct.toString() + " / " + total,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        ConfusionMatrixTable(labels = Conditions.ids, matrix = state.blindConfusion())
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.repository.blindCsvFile.name,
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("重置重测", onReset, Modifier.weight(1f))
            PrimaryButton("返回首页", { state.screen = Screen.Home }, Modifier.weight(1f))
        }
    }
}