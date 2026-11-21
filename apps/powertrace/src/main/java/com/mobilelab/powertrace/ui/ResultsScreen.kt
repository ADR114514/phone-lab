package com.mobilelab.powertrace.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.ui.BarSeries
import com.mobilelab.core.ui.EmptyState
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
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.ConditionSummary
import com.mobilelab.powertrace.data.ExportManager
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SessionSample
import com.mobilelab.powertrace.service.SamplingControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 结果页：4 工况汇总卡片 + 24 行合并表预览 + 导出/分享/逐文件导出 + 单工况补测。
 *
 * 汇总与批次 CSV 都从已落盘的 session CSV 重新计算，保证"数据可溯源"。
 */
@Composable
fun ResultsScreen(
    campaign: CampaignState?,
    onBack: () -> Unit,
    onRerunStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberFileExporter()

    var bundle by remember { mutableStateOf<ExportManager.ResultsBundle?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var rerunTarget by remember { mutableStateOf<ConditionId?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(campaign?.campaignId, refreshKey) {
        if (campaign == null) {
            bundle = null
            files = emptyList()
            return@LaunchedEffect
        }
        bundle = PowerTraceController.loadResults(writeDerived = true)
        files = withContext(Dispatchers.IO) { PowerTraceController.exportableFiles() }
    }

    if (campaign == null) {
        LabScaffold(title = "结果与导出", onBack = onBack) {
            EmptyState("暂无批次数据", "完成一次采集后可在这里查看 4 工况对比并导出")
        }
        return
    }

    val data = bundle
    val active = campaign.status == CampaignStatus.RUNNING || campaign.status == CampaignStatus.PAUSED

    LabScaffold(
        title = "结果与导出",
        subtitle = "${campaign.campaignId} · 会话 ${campaign.sessionId}",
        status = "${campaign.status.label} · ${campaign.totalSamplesDone} 行采样 · ${files.size} 个文件",
        onBack = onBack,
        bottomBar = {
            BottomActions {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(text = "返回", onClick = onBack)
                    PrimaryButton(
                        text = "一键分享全部 ZIP",
                        onClick = {
                            if (busy) return@PrimaryButton
                            busy = true
                            scope.launch {
                                val zip = PowerTraceController.zipBundle()
                                busy = false
                                if (zip == null) {
                                    message = "打包失败：目录不存在或无权限"
                                } else {
                                    files = withContext(Dispatchers.IO) { PowerTraceController.exportableFiles() }
                                    exporter.share(zip, "application/zip")
                                    message = "已生成 ZIP：${zip.name}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = busy,
                    )
                }
            }
        },
    ) {
        ScreenScroll {
            if (message != null) {
                Text(text = message!!, style = MaterialTheme.typography.bodySmall, color = LabColors.Success)
            }

            if (data == null) {
                SectionCard(title = "加载中") { HintText("正在从会话目录读取采样行…") }
            } else {
                SectionCard(title = "4 工况汇总") {
                    if (data.summaries.isEmpty()) {
                        HintText("暂无已完成的工况")
                    } else {
                        BarSeries(
                            items = data.summaries.map { summary ->
                                summary.condition.label to (summary.avgPowerW?.toFloat() ?: 0f)
                            },
                            unit = "W（平均功率）",
                            color = LabColors.Primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        KeyValueRow("工况数", "${data.summaries.size}")
                        KeyValueRow("采样行数", "${data.summaries.sumOf { it.sampleCount }} / ${data.summaries.size * SamplingConfig.SAMPLES_PER_CONDITION}")
                        KeyValueRow(
                            "批次 CSV 行数",
                            data.samplesByCondition.values.sumOf { it.size }.toString() + " 行（4 工况完成时应为 24 行）",
                        )
                        KeyValueRow("数据目录", "experiments/powertrace/${File(campaign.dirPath).name}")
                    }
                }

                data.summaries.forEach { summary ->
                    SummaryCard(
                        summary = summary,
                        canRerun = !active,
                        onRerun = { rerunTarget = summary.condition },
                    )
                }

                SectionCard(title = "24 行合并表预览（批次 CSV 结构）") {
                    HintText("列序：campaign_id, block, 以及会话 CSV 的 31 列（去掉 session_id）。导出文件包含全部列。")
                    Spacer(Modifier.height(6.dp))
                    PreviewTable(data.state, data.samplesByCondition)
                }

                SectionCard(title = "导出") {
                    HintText("目录结构符合数据字典 1.5；导出前会自动刷新派生 CSV 与 manifest.json。")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = "批次 CSV",
                            onClick = {
                                val file = data.campaignCsv
                                if (file == null) message = "批次 CSV 尚未生成" else exporter.saveAs(file, "text/csv")
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "汇总 CSV",
                            onClick = {
                                val file = data.summaryCsv
                                if (file == null) message = "汇总 CSV 尚未生成" else exporter.saveAs(file, "text/csv")
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = "分享批次 CSV",
                            onClick = {
                                data.campaignCsv?.let { exporter.share(it, "text/csv") }
                                    ?: run { message = "批次 CSV 尚未生成" }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "分享汇总 CSV",
                            onClick = {
                                data.summaryCsv?.let { exporter.share(it, "text/csv") }
                                    ?: run { message = "汇总 CSV 尚未生成" }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinkButton(
                        text = "重新读取并刷新派生文件",
                        onClick = { refreshKey++ },
                    )
                }


                SectionCard(title = "删除数据（不可恢复）", accent = LabColors.Error) {
                    HintText("删除错误批次：会删除本批次会话目录（CSV / session.log / manifest）并清空当前批次状态，之后可重新新建批次测量。")
                    if (active) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill("采集中/已暂停：请先在采集页暂停或放弃批次，再删除", PillTone.Warning)
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = "删除本批次数据",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !active && !busy,
                    )
                }
                SectionCard(title = "逐文件导出（${files.size}）") {
                    if (files.isEmpty()) {
                        HintText("目录中暂无文件")
                    } else {
                        files.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LabColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = "${file.length()} B",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LabColors.TextSecondary,
                                    )
                                }
                                LinkButton(
                                    text = "分享",
                                    onClick = { exporter.share(file, mimeOf(file)) },
                                )
                                LinkButton(
                                    text = "另存",
                                    onClick = { exporter.saveAs(file, mimeOf(file)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除本批次数据？") },
            text = {
                Text(
                    "将永久删除批次 " + campaign.campaignId + " 的会话目录与 " + campaign.totalSamplesDone +
                        " 行已采数据，操作不可撤销。建议先导出 ZIP 备份。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        val ok = PowerTraceController.deleteCampaignData()
                        busy = false
                        if (ok) {
                            onBack()
                        } else {
                            message = "删除失败：批次不存在或仍在采集中"
                        }
                    }
                }) { Text("确认删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (rerunTarget != null) {
        val target = rerunTarget!!
        AlertDialog(
            onDismissRequest = { rerunTarget = null },
            title = { Text("补测「${target.label}」？") },
            text = {
                Text(
                    "将新建一个单工况批次（30 min，6 个采样点），原批次数据不受影响。" +
                        "补测批次的 manifest 会记录 rerunOf = ${campaign.campaignId}。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rerunTarget = null
                    scope.launch {
                        PowerTraceController.createRerunCampaign(target, notes = "")
                        SamplingControl.start(context)
                        onRerunStarted()
                    }
                }) { Text("开始补测") }
            },
            dismissButton = { TextButton(onClick = { rerunTarget = null }) { Text("取消") } },
        )
    }
}

/** 单个工况的汇总卡片：平均功率、能量、掉电、温升、thermal 最高。 */
@Composable
private fun SummaryCard(summary: ConditionSummary, canRerun: Boolean, onRerun: () -> Unit) {
    SectionCard(
        title = "${summary.block} · ${summary.condition.label}",
        accent = if (summary.sampleCount >= SamplingConfig.SAMPLES_PER_CONDITION) LabColors.TextPrimary else LabColors.Tertiary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = "${summary.sampleCount}/${SamplingConfig.SAMPLES_PER_CONDITION} 点",
                tone = if (summary.sampleCount >= SamplingConfig.SAMPLES_PER_CONDITION) PillTone.Success else PillTone.Warning,
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(text = "thermal ${summary.thermalMax ?: "-"}", tone = thermalTone(summary.thermalMax))
            Spacer(Modifier.weight(1f))
            Text(
                text = "时长 ${fmtOrDash(summary.durationMin, 1)} min",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "平均功率",
                value = fmtOrDash(summary.avgPowerW, 2),
                unit = "W",
                modifier = Modifier.weight(1f),
                accent = LabColors.Primary,
            )
            MetricTile(
                label = "能量",
                value = fmtOrDash(summary.energyWh, 2),
                unit = "Wh",
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "掉电",
                value = summary.levelDropPct?.toString() ?: "--",
                unit = "%",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "温升",
                value = fmtOrDash(summary.tempRiseC, 1),
                unit = "°C",
                modifier = Modifier.weight(1f),
                accent = if ((summary.tempRiseC ?: 0.0) >= 8.0) LabColors.Error else LabColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("起点/终点电量", "${intOrDash(summary.startLevelPct)}% → ${intOrDash(summary.endLevelPct)}%")
        KeyValueRow("起点/终点温度", "${fmtOrDash(summary.tempStartC, 1)} → ${fmtOrDash(summary.tempEndC, 1)} °C")
        KeyValueRow("峰值功率 / 平均电流", "${fmtOrDash(summary.peakPowerW, 2)} W / ${fmtOrDash(summary.avgCurrentMa, 1)} mA")
        KeyValueRow("thermal 上升事件", intOrDash(summary.thermalRiseEvents))
        KeyValueRow("平均 CPU 频率 / 亮度", "${intOrDash(summary.avgCpuFreqMhz)} MHz / ${intOrDash(summary.avgScreenBrightnessPct)}%")
        KeyValueRow("网络 rx / tx", "${fmtOrDash(summary.netRxMb, 1)} / ${fmtOrDash(summary.netTxMb, 1)} MB")
        if (summary.notes.isNotBlank()) HintText("备注：${summary.notes}")
        if (canRerun) {
            Spacer(Modifier.height(4.dp))
            LinkButton(text = "重新运行该工况（补测）", onClick = onRerun)
        }
    }
}

/** 24 行合并表预览：横屏滑动查看关键列。 */
@Composable
private fun PreviewTable(state: CampaignState, samples: Map<ConditionId, List<SessionSample>>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            listOf("block", "condition", "#", "elapsed", "level%", "current_mA", "power_W", "temp_C", "thermal", "note").forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    modifier = Modifier.width(84.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        var rowCount = 0
        state.conditions.forEach { progress ->
            val rows = samples[progress.condition].orEmpty().sortedBy { it.sampleIndex }
            rows.forEach { sample ->
                rowCount++
                Row(Modifier.fillMaxWidth()) {
                    Text(progress.block.toString(), style = MaterialTheme.typography.labelSmall, color = LabColors.Primary, modifier = Modifier.width(84.dp))
                    Text(progress.condition.id, style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text("${sample.sampleIndex}", style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text("${sample.elapsedSec}", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.width(84.dp))
                    Text(intOrDash(sample.batteryLevelPct), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(
                        text = sample.currentNowUa?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(fmtOrDash(sample.powerW, 2), style = MaterialTheme.typography.labelSmall, color = LabColors.Primary, modifier = Modifier.width(84.dp))
                    Text(fmtOrDash(sample.batteryTempC, 1), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(intOrDash(sample.thermalStatus), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(sample.note, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.width(220.dp))
                }
            }
        }
        if (rowCount == 0) {
            Text("暂无采样行", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 $rowCount 行（完整 4 工况 = 24 行）",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
            )
        }
    }
}

private fun mimeOf(file: File): String = when {
    file.name.endsWith(".csv") -> "text/csv"
    file.name.endsWith(".json") -> "application/json"
    file.name.endsWith(".zip") -> "application/zip"
    file.name.endsWith(".log") -> "text/plain"
    else -> "*/*"
}
