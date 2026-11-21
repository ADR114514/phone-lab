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
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.model.Conditions

/**
 * 首页按“实验流程”组织：先看下一步该干什么 → 数据集进度 → 快捷入口 → 设备/说明。
 * 所有门禁（盲测需要数据、评估需要采集完成）都直接在界面上说明原因，避免用户点不动却不知道原因。
 */
@Composable
fun HomeScreen(state: AppState, permission: PermissionRequester) {
    val exporter = rememberFileExporter()
    val counts = state.countsByCondition()
    val total = counts.values.sum()
    val target = Conditions.all.size * Conditions.TARGET_GROUPS
    val blindTotal = state.blindSession?.total ?: (Conditions.all.size * 3)
    val blindDone = state.blindResults.size
    val hasLoocv = state.loocv != null
    val hasNoise = state.noiseResult != null
    val hasEvaluation = state.evaluationFile != null
    val nextConditionId = Conditions.ids.firstOrNull { (counts[it] ?: 0) < Conditions.TARGET_GROUPS } ?: state.initialCondition()
    val nextCondition = Conditions.byId(nextConditionId)
    val nextGroup = state.nextGroup(nextConditionId) ?: 0
    var showExport by remember { mutableStateOf(false) }

    LaunchedEffect(state.exportFile) {
        if (state.exportFile != null) showExport = true
    }

    LabScaffold(
        title = "声振诊断仪",
        subtitle = "项目三 · 旋转机械异常声振诊断",
        status = if (total >= target) "数据集已完成 " + total + "/" + target else "本会话进度 " + total + "/" + target,
    ) {
        ScrollColumn {
            if (!permission.allGranted) {
                PermissionCard(permission)
            }
            NextStepCard(
                state = state,
                total = total,
                target = target,
                blindDone = blindDone,
                blindTotal = blindTotal,
                hasLoocv = hasLoocv,
                hasNoise = hasNoise,
                hasEvaluation = hasEvaluation,
                nextConditionLabel = nextCondition?.label ?: nextConditionId,
                nextGroup = nextGroup,
                nextCount = counts[nextConditionId] ?: 0,
            )
            WorkflowCard(
                total = total,
                target = target,
                blindDone = blindDone,
                blindTotal = blindTotal,
                hasLoocv = hasLoocv,
                hasNoise = hasNoise,
                hasEvaluation = hasEvaluation,
            )
            ProgressCard(counts = counts, total = total, target = target, nextConditionId = nextConditionId)
            CaptureTipsCard(state)
            QuickActionsCard(state = state, total = total)
            SessionCard(state = state, total = total, target = target)
            CapabilityCard(state)
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
private fun NextStepCard(
    state: AppState,
    total: Int,
    target: Int,
    blindDone: Int,
    blindTotal: Int,
    hasLoocv: Boolean,
    hasNoise: Boolean,
    hasEvaluation: Boolean,
    nextConditionLabel: String,
    nextGroup: Int,
    nextCount: Int,
) {
    SectionCard(title = "下一步", accent = LabColors.Primary) {
        when {
            total < target -> {
                StatusPill("步骤 1 / 4 · 采集数据集", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "继续采集：" + nextConditionLabel + " 第 " + nextGroup.coerceAtLeast(1) + " 组",
                    style = MaterialTheme.typography.titleMedium,
                    color = LabColors.TextPrimary,
                )
                HintText(
                    "总进度 " + total + "/" + target + "，当前工况 " + nextCount + "/" + Conditions.TARGET_GROUPS +
                        "。每组 " + state.settings.durationS + " 秒，采集时手机固定在 30 cm 处不要移动。",
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "开始录制本组",
                    onClick = { state.screen = Screen.Record },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("查看数据集", { state.screen = Screen.Dataset }, Modifier.fillMaxWidth())
            }
            blindDone < blindTotal -> {
                StatusPill("步骤 2 / 4 · 盲测", PillTone.Warning)
                Spacer(Modifier.height(8.dp))
                Text("数据集已齐：" + total + "/" + target + " 组", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("接着做盲测：共 " + blindTotal + " 组，界面不显示真实标签，每组先预测再揭示；建议同时录屏 2–4 分钟作为交付视频。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("进入盲测（" + blindDone + "/" + blindTotal + "）", { state.screen = Screen.BlindTest }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("先看数据集", { state.screen = Screen.Dataset }, Modifier.fillMaxWidth())
            }
            !hasLoocv || !hasNoise || !hasEvaluation -> {
                StatusPill("步骤 3 / 4 · 评估与报告", PillTone.Warning)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !hasLoocv -> "运行留一交叉验证（LOOCV）"
                        !hasNoise -> "运行白/粉红噪声抗噪评估"
                        else -> "生成评估报告（JSON + 图表）"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = LabColors.TextPrimary,
                )
                HintText(
                    "评估前确认 5 工况各 15 组已完成；评估页会给出准确率、混淆矩阵与抗噪曲线，" +
                        "结果写入 evaluation JSON，导出 ZIP 时会一并带上。",
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("进入评估与报告", { state.screen = Screen.Evaluation }, Modifier.fillMaxWidth())
            }
            else -> {
                StatusPill("步骤 4 / 4 · 导出交付", PillTone.Success)
                Spacer(Modifier.height(8.dp))
                Text("数据、盲测与评估都已完成", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("导出 ZIP 包含 WAV/加速度/特征/盲测/抗噪/评估 JSON；报告里的准确率截图请从评估页获取。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("导出完整数据集 ZIP", { state.createSessionZipAsync() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("重新查看评估", { state.screen = Screen.Evaluation }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    total: Int,
    target: Int,
    blindDone: Int,
    blindTotal: Int,
    hasLoocv: Boolean,
    hasNoise: Boolean,
    hasEvaluation: Boolean,
) {
    SectionCard(title = "实验流程") {
        StepRow(1, "采集数据集", "5 工况 × 15 组 = 75 组；每组 15 s", total >= target, total >= target, total.toString() + "/" + target)
        StepRow(2, "盲测", "15 组，不显示真实标签，预测后揭示", blindDone >= blindTotal, total >= target && blindDone < blindTotal, blindDone.toString() + "/" + blindTotal)
        StepRow(
            3,
            "评估与报告",
            "LOOCV 准确率 + 混淆矩阵 + 白/粉红噪声抗噪曲线",
            hasLoocv && hasNoise && hasEvaluation,
            total >= target && blindDone >= blindTotal && !(hasLoocv && hasNoise && hasEvaluation),
            if (hasEvaluation) "已完成" else if (hasLoocv) "已算 LOOCV" else "未开始",
        )
        StepRow(4, "导出交付", "ZIP = 数据 + 盲测 + 评估 JSON", hasEvaluation, total >= target && blindDone >= blindTotal && hasEvaluation, if (hasEvaluation) "可导出" else "待评估")
    }
}

@Composable
private fun StepRow(step: Int, title: String, detail: String, done: Boolean, current: Boolean, progress: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(step.toString(), if (done) PillTone.Success else if (current) PillTone.Info else PillTone.Muted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
            HintText(detail)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(progress, if (done) PillTone.Success else if (current) PillTone.Warning else PillTone.Muted)
    }
}

@Composable
private fun ProgressCard(counts: Map<String, Int>, total: Int, target: Int, nextConditionId: String) {
    SectionCard(title = "5 工况数据集进度") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("总进度", style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(total.toString() + " / " + target, style = MaterialTheme.typography.labelMedium, color = LabColors.TextSecondary)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { (total.toFloat() / target).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (total >= target) LabColors.Success else LabColors.Primary,
            trackColor = LabColors.SurfaceHigh,
        )
        Spacer(Modifier.height(12.dp))
        for (condition in Conditions.all) {
            val count = (counts[condition.id] ?: 0).coerceAtMost(Conditions.TARGET_GROUPS)
            val isNext = condition.id == nextConditionId && count < Conditions.TARGET_GROUPS
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = condition.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isNext) {
                        StatusPill("下一组", PillTone.Info)
                        Spacer(Modifier.width(6.dp))
                    }
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
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { count.toFloat() / Conditions.TARGET_GROUPS },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (count >= Conditions.TARGET_GROUPS) LabColors.Success else LabColors.Primary,
                    trackColor = LabColors.SurfaceHigh,
                )
                HintText(condition.setup)
            }
        }
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
private fun CaptureTipsCard(state: AppState) {
    SectionCard(title = "操作要点") {
        HintText("1. 手机固定在设备正前方约 30 cm，麦克风朝向声源；组间不要移动手机。")
        HintText("2. 环境安静（<40 dBA），录音时不要说话；每组 " + state.settings.durationS + " 秒内保持不动。")
        HintText("3. 异常组（削波、中断、明显噪声）直接重录，不要凑数。")
        HintText("4. 盲测时建议录屏 2–4 分钟；抗噪评估在分析端做，不需要在嘈杂环境重录。")
    }
}

@Composable
private fun SessionCard(state: AppState, total: Int, target: Int) {
    SectionCard(title = "实验会话") {
        KeyValueRow("会话 ID", state.repository.sessionId)
        KeyValueRow("样本总数", total.toString() + " / " + target)
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
private fun QuickActionsCard(state: AppState, total: Int) {
    val blindUnlocked = total >= Conditions.all.size * 5
    SectionCard(title = "快捷入口") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("盲测", { state.screen = Screen.BlindTest }, Modifier.weight(1f), enabled = blindUnlocked)
            SecondaryButton("评估与报告", { state.screen = Screen.Evaluation }, Modifier.weight(1f))
        }
        if (!blindUnlocked) {
            HintText("盲测需先完成至少 25 组（每工况 5 组）数据，当前 " + total + " 组。")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("数据集", { state.screen = Screen.Dataset }, Modifier.weight(1f))
            SecondaryButton("导出 ZIP", { state.createSessionZipAsync() }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("设置", { state.screen = Screen.Settings }, Modifier.fillMaxWidth())
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
