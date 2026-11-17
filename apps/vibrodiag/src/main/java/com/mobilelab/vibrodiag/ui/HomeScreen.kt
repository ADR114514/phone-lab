package com.mobilelab.vibrodiag.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.files.FileExporter
import com.mobilelab.core.files.Storage
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.permission.PermissionRequester
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.model.Conditions

@Composable
fun HomeScreen(state: AppState, permission: PermissionRequester) {
    val exporter = rememberFileExporter()
    val counts = state.countsByCondition()
    val total = counts.values.sum()
    val targetTotal = Conditions.all.size * Conditions.TARGET_GROUPS
    var showExport by remember { mutableStateOf(false) }

    LaunchedEffect(state.exportFile) {
        if (state.exportFile != null) showExport = true
    }

    LabScaffold(
        title = "声振诊断仪",
        subtitle = "项目三 · 旋转机械异常声振诊断",
        status = if (total >= targetTotal) "数据集已完成 " + total + "/" + targetTotal else "本会话进度 " + total + "/" + targetTotal,
    ) {
        ScrollColumn {
            SessionCard(state, total)
            if (!permission.allGranted) {
                PermissionCard(permission)
            }
            ProgressCard(state, counts)
            CapabilityCard(state)
            ActionsCard(state)
            AboutCard(state)
        }
    }

    if (showExport) {
        ExportDialog(
            state = state,
            exporter = exporter,
            onDismiss = {
                showExport = false
                state.clearExportFile()
            },
        )
    }
}

@Composable
private fun SessionCard(state: AppState, total: Int) {
    SectionCard(title = "实验会话") {
        KeyValueRow("会话 ID", state.repository.sessionId)
        KeyValueRow("样本总数", total.toString() + " / " + (Conditions.all.size * Conditions.TARGET_GROUPS))
        KeyValueRow("录音时长", state.settings.durationS.toString() + " s")
        KeyValueRow("音频源", state.audioCapability.sourceLabel)
        KeyValueRow("加速度模式", state.settings.accelMode.label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.repository.root.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PermissionCard(permission: PermissionRequester) {
    SectionCard(title = "麦克风权限", accent = LabColors.Tertiary) {
        Text(
            text = "采集声振信号需要 RECORD_AUDIO 权限，录音与特征提取全部在本机完成。",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = "授予麦克风权限",
            onClick = { permission.request() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressCard(state: AppState, counts: Map<String, Int>) {
    SectionCard(title = "5 工况数据集进度") {
        for (condition in Conditions.all) {
            val count = (counts[condition.id] ?: 0).coerceAtMost(Conditions.TARGET_GROUPS)
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = condition.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (count >= Conditions.TARGET_GROUPS) {
                        StatusPill("已完成", PillTone.Success)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = count.toString() + "/" + Conditions.TARGET_GROUPS,
                        style = MaterialTheme.typography.labelMedium,
                        color = LabColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { count.toFloat() / Conditions.TARGET_GROUPS },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (count >= Conditions.TARGET_GROUPS) LabColors.Success else LabColors.Primary,
                    trackColor = LabColors.SurfaceHigh,
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(state: AppState) {
    val audio = state.audioCapability
    val accel = state.accelCapability
    SectionCard(title = "设备能力") {
        KeyValueRow(
            "麦克风采样率",
            if (audio.supports44100) "44.1 kHz 支持" else "44.1 kHz 不支持",
            if (audio.supports44100) LabColors.Success else LabColors.Error,
        )
        KeyValueRow("音频源", audio.sourceLabel)
        KeyValueRow("系统输出采样率", if (audio.outputSampleRateHz > 0) audio.outputSampleRateHz.toString() + " Hz" else "--")
        KeyValueRow("加速度计", accel.sensorName)
        KeyValueRow("最高采样率", Fmt.value(accel.maxRateHz, 0) + " Hz")
        KeyValueRow(
            "200 Hz 支持",
            if (accel.supports200Hz) "是" else "否",
            if (accel.supports200Hz) LabColors.Success else LabColors.Tertiary,
        )
        KeyValueRow("本次请求周期", accel.resolvedPeriodUs.toString() + " us")
        HintText(accel.note)
        HintText(audio.note)
    }
}

@Composable
private fun ActionsCard(state: AppState) {
    SectionCard(title = "快捷操作") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("开始采集", { state.screen = Screen.Record }, Modifier.weight(1f))
            PrimaryButton("盲测", { state.screen = Screen.BlindTest }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("评估与报告", { state.screen = Screen.Evaluation }, Modifier.weight(1f))
            SecondaryButton("数据集", { state.screen = Screen.Dataset }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("导出 ZIP", { state.createSessionZipAsync() }, Modifier.weight(1f))
            SecondaryButton("设置", { state.screen = Screen.Settings }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AboutCard(state: AppState) {
    SectionCard(title = "项目说明") {
        Text(
            text = "用手机麦克风 + 加速度计采集电风扇/吹风机 5 种工况的声振信号，每组 15 次、每次 " +
                state.settings.durationS.toString() + " 秒；App 内完成 Hann 窗 FFT 特征提取、z-score + kNN(k=3) " +
                "分类、留一法评估、15 组盲测与白/粉红噪声抗噪评估，并导出可复现的数据集与评估 JSON。",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        HintText("所有录音、特征与报告仅保存在本机 App 私有目录，可通过 ZIP 导出。")
        HintText("版本 " + com.mobilelab.vibrodiag.BuildConfig.VERSION_NAME + " · 数据契约 phonelab.vibro/1")
    }
}

@Composable
private fun ExportDialog(state: AppState, exporter: FileExporter, onDismiss: () -> Unit) {
    val file = state.exportFile
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LabColors.Surface,
        title = { Text("导出数据集", color = LabColors.TextPrimary) },
        text = {
            Column {
                Text(
                    text = state.repository.sessionId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText("ZIP 包含 manifest、5 工况录音 WAV/加速度 CSV/特征 CSV/meta、盲测与抗噪 CSV、评估 JSON 与 session.log。")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (file == null) "正在生成 ZIP…" else "大小 " + Storage.humanBytes(file.length()),
                    style = MaterialTheme.typography.labelMedium,
                    color = LabColors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                LinkButton("保存到文件…", {
                    file?.let { exporter.saveAs(it, "application/zip") }
                })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    file?.let { exporter.share(it, "application/zip") }
                },
                enabled = file != null,
            ) { Text("分享", color = LabColors.Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = LabColors.TextSecondary) }
        },
    )
}
