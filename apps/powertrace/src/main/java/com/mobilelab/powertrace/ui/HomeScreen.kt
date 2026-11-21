package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.core.ui.EmptyState
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
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SystemAccess
import com.mobilelab.powertrace.service.SamplingControl
import kotlinx.coroutines.launch

/** 首页：项目说明 + 当前批次状态 + 设备信息 + 四工况操作提示。 */
@Composable
fun HomeScreen(
    campaign: CampaignState?,
    access: SystemAccess.Snapshot,
    device: DeviceSnapshot?,
    statusLine: String,
    onNavigate: (Route) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LabScaffold(
        title = "功耗热测仪",
        subtitle = "课题02 · 4 工况 × 30 min × 每 5 min 采样",
        status = statusLine,
    ) {
        ScreenScroll {
            NextStepCard(
                campaign = campaign,
                access = access,
                onNavigate = onNavigate,
                onStart = {
                    scope.launch {
                        PowerTraceController.startCampaign()
                        SamplingControl.start(context)
                        onNavigate(Route.SAMPLING)
                    }
                },
                onResume = {
                    scope.launch {
                        PowerTraceController.resumeCampaign()
                        SamplingControl.resume(context)
                        onNavigate(Route.SAMPLING)
                    }
                },
            )
            ProgressCard(campaign)

            SectionCard(title = "项目说明") {
                HintText(
                    "在 Android 12+ 真机上对「待机 / 短视频 / 导航拍摄 / 高帧率游戏」四种工况各连续测量 30 min，" +
                        "每 5 min 记录一次电量、电流、电压、温度、thermal、CPU 频率、网络与内存等指标，" +
                        "生成 6 行/工况的会话 CSV、24 行批次 CSV 与工况汇总，供 Python 分析管线使用。",
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    "采样间隔固定 ${SamplingConfig.INTERVAL_SEC} s · 单工况 ${SamplingConfig.CONDITION_DURATION_SEC / 60} min · " +
                        "全部数值来自系统 API，不可用字段留空并在 session.log 说明。",
                )
            }

            SectionCard(title = "当前批次") {
                if (campaign == null) {
                    EmptyState("暂无进行中的批次", "点击下方「新建批次」进入向导：选择工况顺序、填写备注、检查权限")
                    PrimaryButton(
                        text = "新建批次",
                        onClick = { onNavigate(Route.WIZARD) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CampaignSummary(campaign)
                    Spacer(Modifier.height(10.dp))
                    when (campaign.status) {
                        CampaignStatus.DRAFT -> PrimaryButton(
                            text = "开始采集",
                            onClick = {
                                scope.launch {
                                    PowerTraceController.startCampaign()
                                    SamplingControl.start(context)
                                    onNavigate(Route.SAMPLING)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        CampaignStatus.RUNNING -> PrimaryButton(
                            text = "进入采集页",
                            onClick = { onNavigate(Route.SAMPLING) },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        CampaignStatus.PAUSED -> PrimaryButton(
                            text = "继续批次",
                            onClick = {
                                scope.launch {
                                    PowerTraceController.resumeCampaign()
                                    SamplingControl.resume(context)
                                    onNavigate(Route.SAMPLING)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        CampaignStatus.FINISHED, CampaignStatus.ABORTED -> {
                            PrimaryButton(
                                text = "查看数据",
                                onClick = { onNavigate(Route.RESULTS) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(
                                text = "新建批次",
                                onClick = { onNavigate(Route.WIZARD) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (campaign.status != CampaignStatus.FINISHED && campaign.status != CampaignStatus.ABORTED) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinkButton(text = "查看数据", onClick = { onNavigate(Route.RESULTS) })
                            LinkButton(text = "设置", onClick = { onNavigate(Route.SETTINGS) })
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        LinkButton(text = "设置", onClick = { onNavigate(Route.SETTINGS) })
                    }
                }
            }

            if (access.warnings.isNotEmpty()) {
                SectionCard(title = "采集质量提示") {
                    access.warnings.forEach { warning ->
                        Text(
                            text = "· $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabColors.Tertiary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinkButton(text = "前往设置", onClick = { onNavigate(Route.SETTINGS) })
                }
            }

            SectionCard(title = "设备信息") {
                if (device == null) {
                    HintText("设备信息读取失败（不影响采集）")
                } else {
                    KeyValueRow("机型", "${device.manufacturer} ${device.model}")
                    KeyValueRow("系统", "Android ${device.androidRelease} (API ${device.sdkInt})")
                    KeyValueRow("SoC", "${device.socManufacturer} ${device.socModel}".trim())
                    KeyValueRow("刷新率", if (device.refreshRateHz > 0f) "${fmtOrDash(device.refreshRateHz.toDouble())} Hz" else "--")
                    KeyValueRow("屏幕", "${device.screenWidthPx}×${device.screenHeightPx} @ ${device.densityDpi}dpi")
                    KeyValueRow(
                        "电池设计容量",
                        if (device.batteryCapacityMah > 0) "${device.batteryCapacityMah} mAh" else "系统未提供",
                    )
                    KeyValueRow("ABI", device.abi)
                    KeyValueRow("时区", device.timeZone)
                }
            }

            SectionCard(title = "四种工况与操作提示") {
                ConditionId.defaultOrder.forEachIndexed { index, condition ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = ConditionId.blockLetter(index).toString(), tone = PillTone.Info)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${condition.label}（${condition.id}）",
                            style = MaterialTheme.typography.titleMedium,
                            color = LabColors.TextPrimary,
                        )
                    }
                    HintText(condition.brief)
                    Spacer(Modifier.height(4.dp))
                    HintText("操作：${condition.operatorTip}")
                    HintText("降温：${condition.coolingTip}")
                    HintText("准备：${condition.prepTip}")
                    if (index != ConditionId.defaultOrder.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun NextStepCard(
    campaign: CampaignState?,
    access: SystemAccess.Snapshot,
    onNavigate: (Route) -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
) {
    val total = campaign?.totalSamplesDone ?: 0
    val target = ConditionId.defaultOrder.size * SamplingConfig.SAMPLES_PER_CONDITION
    SectionCard(title = "下一步", accent = LabColors.Primary) {
        when {
            campaign == null -> {
                StatusPill("步骤 1 / 3 · 新建批次", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text("新建批次开始实验", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("4 工况 × 30 min，共 24 个采样点；开始前会强制检查权限。务必拔掉充电线、关闭自动亮度。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("新建批次", { onNavigate(Route.WIZARD) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("先去设置检查权限", { onNavigate(Route.SETTINGS) }, Modifier.fillMaxWidth())
            }
            campaign.status == CampaignStatus.DRAFT -> {
                StatusPill("步骤 2 / 3 · 开始采集", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text("批次已就绪", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("当前未充电=" + (access.charging != true) + "，自动亮度=" + (access.autoBrightness == true) + "；开始后按工况提示操作，工况间静置 10 分钟降温。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("开始采集", onStart, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("检查采集参数", { onNavigate(Route.WIZARD) }, Modifier.fillMaxWidth())
            }
            campaign.status == CampaignStatus.RUNNING -> {
                StatusPill("步骤 2 / 3 · 采集中", PillTone.Success)
                Spacer(Modifier.height(8.dp))
                val current = campaign.current
                Text(
                    text = if (current != null) "当前 " + current.condition.label + " · 第 " + (current.samplesDone + 1).coerceAtMost(SamplingConfig.SAMPLES_PER_CONDITION) + "/" + SamplingConfig.SAMPLES_PER_CONDITION + " 点" else "采集进行中",
                    style = MaterialTheme.typography.titleMedium,
                    color = LabColors.TextPrimary,
                )
                HintText("已写入 " + total + "/" + target + " 行；待机工况请锁屏静置，其余工况保持屏幕常亮，实验期间不要充电。")
                if (access.charging == true) {
                    Spacer(Modifier.height(4.dp))
                    StatusPill("正在充电：本次数据会作废，请立即拔线", PillTone.Error)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("进入采集页", { onNavigate(Route.SAMPLING) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("查看已采数据", { onNavigate(Route.RESULTS) }, Modifier.fillMaxWidth())
            }
            campaign.status == CampaignStatus.PAUSED -> {
                StatusPill("已暂停", PillTone.Warning)
                Spacer(Modifier.height(8.dp))
                Text("批次已暂停，继续后会从下一个采样点开始", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("已写入 " + total + "/" + target + " 行；继续前确认未充电、亮度已固定。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("继续批次", onResume, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("查看已采数据", { onNavigate(Route.RESULTS) }, Modifier.fillMaxWidth())
            }
            else -> {
                StatusPill("步骤 3 / 3 · 导出分析", PillTone.Info)
                Spacer(Modifier.height(8.dp))
                Text(if (campaign.status == CampaignStatus.FINISHED) "采集已完成" else "批次已放弃（已采数据保留）", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
                HintText("接下来：在结果页核对 24 行数据并导出 ZIP，用 analysis/power 跑分析、填报告（报告模板与自检脚本在仓库）。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("查看数据与导出", { onNavigate(Route.RESULTS) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton("新建批次（补测）", { onNavigate(Route.WIZARD) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProgressCard(campaign: CampaignState?) {
    val target = ConditionId.defaultOrder.size * SamplingConfig.SAMPLES_PER_CONDITION
    val total = campaign?.totalSamplesDone ?: 0
    SectionCard(title = "采集进度") {
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
        Spacer(Modifier.height(10.dp))
        val conditions = campaign?.conditions.orEmpty()
        if (conditions.isEmpty()) {
            HintText("新建批次后，这里会显示 4 个工况的逐点进度。")
        } else {
            conditions.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(c.block + " " + c.condition.label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                    if (c.completed) StatusPill("已完成", PillTone.Success) else if (c.samplesDone > 0) StatusPill("进行中", PillTone.Info) else StatusPill("待采集", PillTone.Muted)
                    Spacer(Modifier.width(8.dp))
                    Text(c.samplesDone.toString() + "/" + SamplingConfig.SAMPLES_PER_CONDITION, style = MaterialTheme.typography.labelMedium, color = LabColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun CampaignSummary(campaign: CampaignState) {
    val current = campaign.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = campaign.campaignId,
            style = MaterialTheme.typography.titleMedium,
            color = LabColors.TextPrimary,
        )
        Spacer(Modifier.width(8.dp))
        StatusPill(
            text = campaign.status.label,
            tone = when (campaign.status) {
                CampaignStatus.RUNNING -> PillTone.Success
                CampaignStatus.PAUSED -> PillTone.Warning
                CampaignStatus.FINISHED -> PillTone.Info
                CampaignStatus.ABORTED -> PillTone.Error
                CampaignStatus.DRAFT -> PillTone.Muted
            },
        )
    }
    HintText("会话 ${campaign.sessionId} · 创建于 ${TimeFmt.isoLocal(campaign.createdAtMs)}")
    if (campaign.rerunOf != null) HintText("补测批次，原批次 ${campaign.rerunOf}")
    Spacer(Modifier.height(8.dp))
    KeyValueRow("已完成工况", "${campaign.conditions.count { it.completed }}/${campaign.conditions.size}")
    KeyValueRow("已写入采样点", "${campaign.totalSamplesDone} 行")
    if (current != null && !current.completed) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "当前：${current.block} ${current.condition.label} · 第 ${(current.samplesDone + 1).coerceAtMost(6)}/${SamplingConfig.SAMPLES_PER_CONDITION} 个采样点",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.Primary,
        )
        Spacer(Modifier.height(4.dp))
        val fraction = if (current.startMs <= 0L) {
            0f
        } else {
            ((current.elapsedMs(System.currentTimeMillis()).toFloat() / SamplingConfig.CONDITION_DURATION_MS).coerceIn(0f, 1f))
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = LabColors.Primary,
            trackColor = LabColors.SurfaceHigh,
        )
    }
    if (campaign.notes.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        HintText("备注：${campaign.notes}")
    }
}
