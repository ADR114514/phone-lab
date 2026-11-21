package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.core.ui.ChartLegend
import com.mobilelab.core.ui.DangerButton
import com.mobilelab.core.ui.EmptyState
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LineChart
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SessionSample
import com.mobilelab.powertrace.service.SamplingControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 采集页：大号实时指标 + 最近 6 点折线（电流/温度）+ 进度倒计时 + 暂停/提前结束/放弃。
 * 数据全部来自已落盘的最近采样行，不做任何插值或预测。
 */
@Composable
fun SamplingScreen(
    campaign: CampaignState?,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by PowerTraceController.latestSample.collectAsState()
    val samples by PowerTraceController.conditionSamples.collectAsState()

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var showAbortDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(campaign?.status) {
        when (campaign?.status) {
            CampaignStatus.FINISHED -> onFinished()
            CampaignStatus.ABORTED -> onBack()
            else -> Unit
        }
    }

    if (campaign == null) {
        LabScaffold(title = "采集", onBack = onBack) {
            EmptyState("没有进行中的批次", "请先在首页新建批次")
        }
        return
    }

    val current = campaign.current
    val running = campaign.status == CampaignStatus.RUNNING
    val paused = campaign.status == CampaignStatus.PAUSED
    val samplesDone = current?.samplesDone ?: 0
    val dueAtMs = CampaignEngine.nextDueAtMs(campaign)
    val remainingMs = dueAtMs?.let { (it - nowMs).coerceAtLeast(0L) }
    val conditionElapsed = current?.elapsedMs(nowMs) ?: 0L

    LabScaffold(
        title = "采集 · ${current?.block ?: '-'} ${current?.condition?.label ?: ""}",
        subtitle = "${campaign.campaignId} · 会话 ${campaign.sessionId}",
        status = when {
            paused -> "已暂停（不写入数据）"
            running && samplesDone >= SamplingConfig.SAMPLES_PER_CONDITION -> "6 点已采完，等待 30 min 收尾"
            running -> "采集中 · 第 ${(samplesDone + 1).coerceAtMost(6)}/${SamplingConfig.SAMPLES_PER_CONDITION} 点"
            else -> campaign.status.label
        },
        onBack = onBack,
        bottomBar = {
            BottomActions {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(
                        text = if (paused) "继续" else "暂停",
                        onClick = {
                            if (paused) {
                                scope.launch {
                                    PowerTraceController.resumeCampaign()
                                    SamplingControl.resume(context)
                                }
                            } else {
                                scope.launch { PowerTraceController.pauseCampaign() }
                                SamplingControl.pause(context)
                            }
                        },
                        enabled = running || paused,
                    )
                    DangerButton(
                        text = "提前结束本工况",
                        onClick = { showSkipDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = running || paused,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkButton(text = "返回首页", onClick = onBack)
                    LinkButton(text = "放弃批次", onClick = { showAbortDialog = true })
                    if (latest == null && running) {
                        LinkButton(text = "重新连接采集服务", onClick = { SamplingControl.sampleNow(context) })
                    }
                }
            }
        },
    ) {
        ScreenScroll {
            SectionCard(title = "实时指标（最近一次采样）") {
                if (latest == null) {
                    HintText("等待第一个采样点写入…（工况开始即采第 1 点）")
                } else {
                    val s = latest!!
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(
                            label = "电流",
                            value = s.currentNowUa?.let { abs(it).let { ua -> "%.0f".format(java.util.Locale.US, ua / 1000.0) } } ?: "--",
                            unit = "mA",
                            modifier = Modifier.weight(1f),
                            accent = LabColors.Primary,
                        )
                        MetricTile(
                            label = "功率",
                            value = fmtOrDash(s.powerW, 2),
                            unit = "W",
                            modifier = Modifier.weight(1f),
                            accent = LabColors.Primary,
                        )
                    }
                    if (s.powerW == null) {
                        HintText("功率需两次电荷计读数：第 1 点留空是正常的，第 2 点（+5 min）起显示；若持续为空请确认已拔掉充电线。")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(
                            label = "电池温度",
                            value = fmtOrDash(s.batteryTempC, 1),
                            unit = "°C",
                            modifier = Modifier.weight(1f),
                            accent = if (s.thermalStatus != null && s.thermalStatus >= 3) LabColors.Error else LabColors.TextPrimary,
                        )
                        MetricTile(
                            label = "thermal status",
                            value = intOrDash(s.thermalStatus),
                            unit = "级",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(
                            label = "CPU 温度",
                            value = fmtOrDash(s.cpuTempC, 1),
                            unit = "°C",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "CPU 频率",
                            value = intOrDash(s.cpuFreqAvgMhz),
                            unit = "MHz",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = thermalLabel(s.thermalStatus), tone = thermalTone(s.thermalStatus))
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "剩余 ${TimeFmt.elapsedMinSec(remainingMs ?: 0L)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = LabColors.Primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    HintText("电量 ${intOrDash(s.batteryLevelPct)}% · 电压 ${intOrDash(s.batteryVoltageMv)} mV · 网络 ${s.netType} · 屏幕 ${if (s.screenOn) "亮" else "熄"}")
                    if (s.note.isNotBlank()) {
                        HintText("note：${s.note}")
                    }
                }
            }

            SectionCard(title = "工况进度") {
                val fraction = (conditionElapsed.toFloat() / SamplingConfig.CONDITION_DURATION_MS).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = LabColors.Primary,
                    trackColor = LabColors.SurfaceHigh,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        paused -> "已暂停：第 $samplesDone/6 点，elapsed ${conditionElapsed / 1000} s"
                        samplesDone >= SamplingConfig.SAMPLES_PER_CONDITION ->
                            "第 6/6 点已写入，距本工况收尾 ${TimeFmt.elapsedMinSec(remainingMs ?: 0L)}"
                        else ->
                            "第 ${(samplesDone + 1).coerceAtMost(6)}/6 个采样点，距下次 ${TimeFmt.elapsedMinSec(remainingMs ?: 0L)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextPrimary,
                )
                HintText("工况 elapsed ${conditionElapsed / 1000} s / ${SamplingConfig.CONDITION_DURATION_SEC} s · 已完成工况 ${campaign.conditions.count { it.completed }}/${campaign.conditions.size}")
                if (current != null && current.lateSamples > 0) {
                    Text(
                        text = "本工况有 ${current.lateSamples} 个迟到样本（已在 note 标记 late_sample）",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.Tertiary,
                    )
                }
            }

            SectionCard(title = "最近 6 点 · 电流 (mA)") {
                val values = samples.mapNotNull { it.currentNowUa?.let { ua -> abs(ua) / 1000.0 } }
                LineChart(values = values.map { it.toFloat() }, color = LabColors.Primary)
                ChartLegend(
                    min = values.minOrNull()?.toFloat(),
                    avg = values.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    max = values.maxOrNull()?.toFloat(),
                    unit = "mA",
                )
            }

            SectionCard(title = "最近 6 点 · 电池温度 (°C)") {
                val values = samples.mapNotNull { it.batteryTempC }
                LineChart(values = values.map { it.toFloat() }, color = LabColors.Tertiary)
                ChartLegend(
                    min = values.minOrNull()?.toFloat(),
                    avg = values.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    max = values.maxOrNull()?.toFloat(),
                    unit = "°C",
                )
            }

            SectionCard(title = "采样点明细") {
                if (samples.isEmpty()) {
                    HintText("暂无采样行")
                } else {
                    samples.sortedBy { it.sampleIndex }.forEach { sample: SessionSample ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = "#${sample.sampleIndex}",
                                style = MaterialTheme.typography.labelMedium,
                                color = LabColors.Primary,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                text = TimeFmt.clock(sample.timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextSecondary,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                text = "${fmtOrDash(sample.powerW, 2)} W",
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextPrimary,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                text = "${fmtOrDash(sample.batteryTempC, 1)} °C",
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextPrimary,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                text = "${intOrDash(sample.batteryLevelPct)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("提前结束本工况？") },
            text = {
                Text("已采集的 $samplesDone 个采样点会保留并照常写入 CSV，note 标记「用户提前结束本工况」。之后立即进入下一工况。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    SamplingControl.skipCondition(context)
                }) { Text("结束并继续") }
            },
            dismissButton = { TextButton(onClick = { showSkipDialog = false }) { Text("取消") } },
        )
    }

    if (showAbortDialog) {
        AlertDialog(
            onDismissRequest = { showAbortDialog = false },
            title = { Text("放弃整个批次？") },
            text = { Text("批次将标记为「已放弃」，已写入的采样数据保留，可继续在结果页查看/导出。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showAbortDialog = false
                    SamplingControl.abort(context)
                }) { Text("放弃批次") }
            },
            dismissButton = { TextButton(onClick = { showAbortDialog = false }) { Text("取消") } },
        )
    }
}
