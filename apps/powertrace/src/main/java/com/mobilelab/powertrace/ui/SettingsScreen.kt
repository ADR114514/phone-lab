package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.BuildConfig
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SystemAccess
import java.io.File

/** 设置页：采样参数（固定）、原始序列开关、权限入口、关于/版本。 */
@Composable
fun SettingsScreen(
    access: SystemAccess.Snapshot,
    campaign: CampaignState?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var rawEnabled by remember { mutableStateOf(PowerTraceController.isRawSeriesEnabled()) }
    var guardEnabled by remember { mutableStateOf(PowerTraceController.isStandbyGuardEnabled()) }
    val running = campaign?.status == com.mobilelab.powertrace.data.CampaignStatus.RUNNING

    LabScaffold(
        title = "设置",
        subtitle = "采样参数、授权入口与版本信息",
        onBack = onBack,
    ) {
        ScreenScroll {
            SectionCard(title = "采样参数") {
                KeyValueRow("采样间隔", "${SamplingConfig.INTERVAL_SEC} s（固定，不可修改）")
                KeyValueRow("单工况时长", "${SamplingConfig.CONDITION_DURATION_SEC / 60} min")
                KeyValueRow("单工况采样点", "${SamplingConfig.SAMPLES_PER_CONDITION} 个")
                KeyValueRow("迟到判定阈值", "${SamplingConfig.LATE_TOLERANCE_MS / 1000} s")
                KeyValueRow("采样持锁上限", "${SamplingConfig.WAKE_LOCK_TIMEOUT_MS / 1000} s")
                Spacer(Modifier.height(6.dp))
                HintText("固定 300 s 间隔是数据契约的一部分，分析管线与历史数据都依赖该口径。")
            }

            SectionCard(title = "原始高频序列（可选）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "每 ${SamplingConfig.RAW_INTERVAL_SEC} s 记录 raw_<工况>.csv",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rawEnabled,
                        onCheckedChange = { checked ->
                            rawEnabled = checked
                            PowerTraceController.setRawSeriesEnabled(checked)
                        },
                        enabled = !running,
                    )
                }
                HintText(
                    if (running) {
                        "批次采集中不可修改；新批次生效。"
                    } else {
                        "默认关闭（30 min ≈ 360 行，写入压力较大）。开启后仅在设备醒着时记录，作为补充数据。"
                    },
                )
            }

            SectionCard(title = "待机准点守护（实验性）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "IDLE 工况持有部分唤醒锁",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextPrimary,
                        )
                        HintText("仅当系统把 5 min 采样点延后（迟到>90 s）时开启；开启后待机功耗含数十 mW CPU 驻留开销，报告中需说明。优先尝试关闭系统睡眠待机优化并允许应用后台活动。")
                    }
                    Switch(
                        checked = guardEnabled,
                        onCheckedChange = { checked ->
                            guardEnabled = checked
                            PowerTraceController.setStandbyGuardEnabled(checked)
                        },
                    )
                }
            }

            SectionCard(title = "授权入口") {
                AccessRow(
                    title = "通知权限",
                    granted = access.notifications,
                    hint = "前台服务进度显示在通知栏",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.openNotificationSettings(context) },
                )
                AccessRow(
                    title = "电池优化豁免",
                    granted = access.batteryUnrestricted,
                    hint = "避免 Doze 延迟采样（待机工况尤其重要）",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestBatteryExemption(context) },
                )
                AccessRow(
                    title = "精确闹钟",
                    granted = access.exactAlarm,
                    hint = "setExactAndAllowWhileIdle 唤醒采样",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestExactAlarmAccess(context) },
                )
                AccessRow(
                    title = "使用情况访问",
                    granted = access.usageStats,
                    hint = "可选：记录 foreground_pkg 前台包名",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.openUsageAccessSettings(context) },
                )
            }

            SectionCard(title = "数据与导出") {
                val root = PowerTraceController.experimentsRoot()
                KeyValueRow(
                    "数据根目录",
                    root?.let { "experiments/powertrace" } ?: "读取失败",
                )
                if (root != null) {
                    KeyValueRow("绝对路径", root.absolutePath)
                }
                campaign?.let { state ->
                    KeyValueRow("当前批次目录", File(state.dirPath).name)
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    "目录结构（数据字典 1.5）：manifest.json、session_<工况>.csv（6 行/工况）、" +
                        "campaign_<批次>.csv（24 行）、campaign_summary_<批次>.csv、可选 raw_<工况>.csv、session.log。",
                )
                HintText("CSV 为 UTF-8 + BOM + CRLF，Excel 与 pandas 可直接打开。导出/分享在结果页操作。")
            }

            SectionCard(title = "关于") {
                KeyValueRow("App", "功耗热测仪 / PowerTrace")
                KeyValueRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueRow("课题", "02 · 多工况电量与发热实测")
                KeyValueRow("包名", BuildConfig.APPLICATION_ID)
                KeyValueRow("目标设备", "Android 12+ (arm64-v8a)")
                Spacer(Modifier.height(6.dp))
                HintText(
                    "功率口径：power_w = voltage_mv × |current_now_ua| / 1e9；" +
                        "能量用矩形积分（6 × 300 s 覆盖 30 min）。不可用字段一律留空，原因写入 session.log 与 note。",
                )
                HintText("数据契约：docs/DATA_DICTIONARY.md 第一节（唯一权威）。")
            }
        }
    }
}
