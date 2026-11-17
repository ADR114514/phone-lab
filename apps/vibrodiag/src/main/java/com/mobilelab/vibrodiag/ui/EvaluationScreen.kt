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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.files.FileExporter
import com.mobilelab.core.files.Storage
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.ui.BarSeries
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.LineChart
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
import com.mobilelab.vibrodiag.data.EvaluationReport
import com.mobilelab.vibrodiag.data.Reports
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.model.Conditions
import java.util.Locale

@Composable
fun EvaluationScreen(state: AppState) {
    val exporter = rememberFileExporter()
    var notes by rememberSaveable { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }

    LaunchedEffect(state.exportFile) {
        if (state.exportFile != null) showExportDialog = true
    }

    LabScaffold(
        title = "评估与报告",
        subtitle = "留一法 · 混淆矩阵 · 白/粉红噪声抗噪",
        status = "训练集 " + state.usableSamples().size + " 组",
        onBack = { state.screen = Screen.Home },
    ) {
        ScrollColumn {
            DatasetStatsCard(state)
            TrainingCard(state)
            NoiseCard(state)
            ReportCard(
                state = state,
                notes = notes,
                onNotesChange = { notes = it },
                onShowJson = {
                    jsonText = state.evaluationReportText()
                    showJsonDialog = true
                },
                onGenerated = { showReportDialog = true },
            )
        }
    }

    if (showExportDialog) {
        val file = state.exportFile
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                state.clearExportFile()
            },
            containerColor = LabColors.Surface,
            title = { Text("导出会话 ZIP", color = LabColors.TextPrimary) },
            text = {
                Column {
                    HintText("ZIP 包含全部录音、特征 CSV、盲测/抗噪 CSV、评估 JSON、manifest 与 session.log。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (file == null) "正在生成…" else Storage.humanBytes(file.length()),
                        style = MaterialTheme.typography.labelMedium,
                        color = LabColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = file != null,
                    onClick = { file?.let { exporter.share(it, "application/zip") } },
                ) { Text("分享", color = LabColors.Primary) }
            },
            dismissButton = {
                Column {
                    LinkButton("保存到文件…", { file?.let { exporter.saveAs(it, "application/zip") } })
                    TextButton(onClick = {
                        showExportDialog = false
                        state.clearExportFile()
                    }) { Text("关闭", color = LabColors.TextSecondary) }
                }
            },
        )
    }

    if (showReportDialog) {
        val file = state.evaluationFile
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = LabColors.Surface,
            title = { Text("评估报告已生成", color = LabColors.Success) },
            text = {
                Column {
                    Text(
                        text = file?.name ?: "evaluation JSON",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText("包含 training(LOOCV/混淆矩阵/逐工况准确率)、blind_test、noise、完整 model 参数与 conditions。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = file?.absolutePath ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkButton("查看 JSON 内容", {
                        jsonText = state.evaluationReportText()
                        showReportDialog = false
                        showJsonDialog = true
                    })
                    LinkButton("保存 JSON…", { file?.let { exporter.saveAs(it, "application/json") } })
                }
            },
            confirmButton = {
                TextButton(onClick = { file?.let { exporter.share(it, "application/json") } }) {
                    Text("分享", color = LabColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("关闭", color = LabColors.TextSecondary) }
            },
        )
    }

    if (showJsonDialog) {
        TextContentDialog("evaluation JSON", jsonText) { showJsonDialog = false }
    }
}

@Composable
private fun DatasetStatsCard(state: AppState) {
    val counts = state.countsByCondition()
    SectionCard(title = "数据集统计") {
        for (condition in Conditions.all) {
            KeyValueRow(condition.label, (counts[condition.id] ?: 0).toString() + " / " + Conditions.TARGET_GROUPS)
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("训练集样本", state.usableSamples().size.toString())
        KeyValueRow("特征维度", com.mobilelab.vibrodiag.features.FeatureNames.count.toString())
        val interrupted = state.samples.count { it.interrupted }
        if (interrupted > 0) {
            Spacer(Modifier.height(4.dp))
            StatusPill("不完整样本 " + interrupted + " 组（不参与训练）", PillTone.Warning)
        }
    }
}

@Composable
private fun TrainingCard(state: AppState) {
    val loocv = state.loocv
    SectionCard(title = "留一法（LOOCV）评估") {
        if (loocv == null) {
            HintText("使用当前全部已标注样本做留一法交叉验证（每折重新计算 z-score 统计量）。")
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                "运行留一法评估",
                { state.runLoocv() },
                Modifier.fillMaxWidth(),
                enabled = state.usableSamples().size >= 2,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "LOOCV 准确率",
                    Fmt.value(loocv.accuracy * 100.0, 1),
                    "%",
                    modifier = Modifier.weight(1f),
                    accent = LabColors.Success,
                )
                MetricTile(
                    "正确 / 总数",
                    loocv.correct.toString() + " / " + loocv.total,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("逐工况准确率", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val perCondition = loocv.perConditionAccuracy()
            BarSeries(
                items = Conditions.all.map { it.shortLabel to ((perCondition[it.id] ?: 0.0) * 100.0).toFloat() },
                color = LabColors.Primary,
                unit = "%",
                highlightIndex = Conditions.all.indices.maxByOrNull { perCondition[Conditions.all[it].id] ?: 0.0 } ?: -1,
            )
            Spacer(Modifier.height(12.dp))
            Text("混淆矩阵（行=真实，列=预测）", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            ConfusionMatrixTable(labels = Conditions.ids, matrix = loocv.confusionMatrix())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("重新计算", { state.runLoocv() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NoiseCard(state: AppState) {
    val result = state.noiseResult
    SectionCard(title = "抗噪评估（白噪声 / 粉红噪声）") {
        HintText("对全部样本按 SNR 20/10/5/0/-5 dB 叠加噪声后重算音频特征再分类；振动特征保持不变。")
        Spacer(Modifier.height(8.dp))
        if (state.noiseRunning) {
            Text(
                "正在提取加噪特征并分类：" + Fmt.value(state.noiseProgress * 100.0, 0) + "%",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.noiseProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = LabColors.Secondary,
                trackColor = LabColors.SurfaceHigh,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("取消", { state.cancelNoiseEvaluation() }, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(
                "运行抗噪评估",
                { state.runNoiseEvaluation() },
                Modifier.fillMaxWidth(),
                enabled = state.usableSamples().isNotEmpty(),
            )
        }
        if (result != null) {
            Spacer(Modifier.height(12.dp))
            val whiteCurve = result.snrLevels.mapNotNull { snr ->
                val value = result.accuracy(NoiseType.WHITE, snr)
                if (value.isFinite()) value.toFloat() else null
            }
            val pinkCurve = result.snrLevels.mapNotNull { snr ->
                val value = result.accuracy(NoiseType.PINK, snr)
                if (value.isFinite()) value.toFloat() else null
            }
            Text("白噪声准确率曲线", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            LineChart(
                values = whiteCurve,
                modifier = Modifier.fillMaxWidth(),
                color = LabColors.Primary,
                height = 110.dp,
                minValue = 0f,
                maxValue = 1f,
            )
            Spacer(Modifier.height(8.dp))
            Text("粉红噪声准确率曲线", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            LineChart(
                values = pinkCurve,
                modifier = Modifier.fillMaxWidth(),
                color = LabColors.Secondary,
                height = 110.dp,
                minValue = 0f,
                maxValue = 1f,
            )
            Spacer(Modifier.height(10.dp))
            Text("SNR 由左到右：" + result.snrLevels.joinToString(" / ") { it.toString() + " dB" }, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("SNR", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
                Text("白噪声", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
                Text("粉红噪声", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
            }
            for (snr in result.snrLevels) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        snr.toString() + " dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Fmt.percent(result.accuracy(NoiseType.WHITE, snr)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Fmt.percent(result.accuracy(NoiseType.PINK, snr)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HintText("逐工况明细见 " + state.repository.noiseCsvFile.name + "（共 " + result.rows.size + " 行）")
        } else {
            Spacer(Modifier.height(8.dp))
            val rows = Reports.readNoiseRows(state.repository.noiseCsvFile)
            HintText(
                if (rows.isEmpty()) "尚未运行抗噪评估。"
                else "已从 noise_eval CSV 读取 " + rows.size + " 行历史结果，可重新生成评估报告。"
            )
        }
    }
}

@Composable
private fun ReportCard(
    state: AppState,
    notes: String,
    onNotesChange: (String) -> Unit,
    onShowJson: () -> Unit,
    onGenerated: () -> Unit,
) {
    SectionCard(title = "评估报告") {
        LabTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = "现场备注（写入 evaluation.notes）",
            placeholder = "如：手机平放，风扇底座 30cm，安静实验室",
            singleLine = false,
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "生成评估报告 JSON",
            {
                if (state.generateEvaluationReport(notes) != null) onGenerated()
            },
            Modifier.fillMaxWidth(),
            enabled = state.usableSamples().isNotEmpty(),
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton("导出会话 ZIP（含全部数据）", { state.createSessionZipAsync() }, Modifier.fillMaxWidth())
        if (state.evaluationFile != null) {
            Spacer(Modifier.height(8.dp))
            LinkButton("查看当前 evaluation JSON", { onShowJson() })
            Text(
                text = state.evaluationFile?.name ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        HintText("评估 JSON 内含训练集统计、LOOCV、盲测、抗噪曲线与完整模型参数，可直接复现实验。")
    }
}