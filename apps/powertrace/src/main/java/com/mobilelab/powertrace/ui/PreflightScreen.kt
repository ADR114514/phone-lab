package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SystemAccess
import android.os.Build

/**
 * 开始采集前的强制检查页。
 *
 * 设计目的：真机实测发现，待机采样能否准点 100% 取决于系统授权（通知、电池优化豁免、精确闹钟、
 * 厂商后台活动/睡眠待机优化）。这些设置分散在系统深处，用户很容易漏掉，导致 30 min 待机工况
 * 只有 1 个点。因此把"开始采集"改成强制门禁：必需项未全部满足时，无法进入批次配置。
 */
@Composable
fun PreflightScreen(
    access: SystemAccess.Snapshot,
    onRefresh: () -> Unit,
    onPassed: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var oemBackground by remember { mutableStateOf(PowerTraceController.isOemBackgroundConfirmed()) }
    var oemSleep by remember { mutableStateOf(PowerTraceController.isOemSleepConfirmed()) }

    val requiredDone = access.notifications && access.batteryUnrestricted && access.exactAlarm &&
        oemBackground && oemSleep
    val brand = Build.MANUFACTURER.lowercase()

    LabScaffold(
        title = "开始前强制检查",
        subtitle = "缺一不可：任一必需项未完成时不能开始采集（避免数据作废）",
        onBack = onBack,
    ) {
        ScreenScroll {
            SectionCard(title = "必需项（全部完成后才能开始）") {
                PermissionRow(
                    label = "通知权限",
                    desc = "前台服务常驻通知；拒绝后采集仍在跑但看不到进度",
                    ok = access.notifications,
                    actionText = "去授权",
                    onAction = { SystemAccess.openNotificationSettings(context) },
                )
                PermissionRow(
                    label = "电池优化豁免",
                    desc = "Doze 会冻结后台，导致 5 min 采样点丢失",
                    ok = access.batteryUnrestricted,
                    actionText = "去设置",
                    onAction = { SystemAccess.requestBatteryExemption(context) },
                )
                PermissionRow(
                    label = "精确闹钟",
                    desc = "熄屏时靠精确闹钟唤醒采样",
                    ok = access.exactAlarm,
                    actionText = "去授权",
                    onAction = { SystemAccess.requestExactAlarmAccess(context) },
                )
            }

            SectionCard(title = "厂商后台限制（必须手动确认）") {
                HintText(
                    "系统无法用 API 检测“睡眠待机优化/后台活动”是否关闭，请按下面步骤操作后勾选确认。" +
                        "不同品牌路径不同，找不到时可在设置搜索框搜“睡眠待机优化”或“后台活动”。"
                )
                Spacer(Modifier.height(8.dp))
                OemCheckRow(
                    checked = oemBackground,
                    label = "已允许「后台活动 / 自启动」",
                    steps = oemBackgroundSteps(brand),
                    onChecked = { checked ->
                        oemBackground = checked
                        PowerTraceController.setOemBackgroundConfirmed(checked)
                    },
                )
                Spacer(Modifier.height(8.dp))
                OemCheckRow(
                    checked = oemSleep,
                    label = "已关闭「睡眠待机优化」",
                    steps = oemSleepSteps(brand),
                    onChecked = { checked ->
                        oemSleep = checked
                        PowerTraceController.setOemSleepConfirmed(checked)
                    },
                )
                Spacer(Modifier.height(6.dp))
                HintText("找不到入口时：设置 → 应用 → 功耗热测仪 → 电池/耗电管理 → 允许后台活动。")
            }

            SectionCard(title = "可选优化") {
                PermissionRow(
                    label = "使用情况访问",
                    desc = "记录前台 App 包名（foreground_pkg），不授权则该列留空",
                    ok = access.usageStats,
                    actionText = "去授权",
                    onAction = { SystemAccess.openUsageAccessSettings(context) },
                    optional = true,
                )
                PermissionRow(
                    label = "屏幕自动亮度关闭",
                    desc = "亮度变化会干扰功耗与温度对比",
                    ok = !access.autoBrightness,
                    actionText = "去设置",
                    onAction = { SystemAccess.openAppDetails(context) },
                    optional = true,
                )
            }

            SectionCard(title = "其它提示") {
                if (access.charging == true) {
                    StatusPill(text = "当前正在充电——功耗数据会作废，实验时必须拔线", tone = PillTone.Warning)
                    Spacer(Modifier.height(6.dp))
                }
                HintText("完成上方设置后回到本页会自动刷新；状态长期不变时点“刷新状态”。")
            }

            Row(Modifier.padding(vertical = 4.dp)) {
                SecondaryButton(text = "刷新状态", onClick = onRefresh, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                PrimaryButton(
                    text = if (requiredDone) "通过检查，去配置批次" else "必需项未完成",
                    onClick = {
                        if (requiredDone) {
                            onPassed()
                        } else {
                            onRefresh()
                        }
                    },
                    enabled = requiredDone,
                    modifier = Modifier.weight(2f),
                )
            }
            HintText("说明：本 App 不联网、不上传数据；以上设置只影响采样可靠性。")
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    desc: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
                Spacer(Modifier.width(6.dp))
                if (optional) {
                    StatusPill(text = "可选", tone = PillTone.Muted)
                } else {
                    StatusPill(
                        text = if (ok) "已完成" else "必须处理",
                        tone = if (ok) PillTone.Success else PillTone.Error,
                    )
                }
            }
            HintText(desc)
        }
        if (!ok) {
            SecondaryButton(text = actionText, onClick = onAction, modifier = Modifier.width(96.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun OemCheckRow(
    checked: Boolean,
    label: String,
    steps: String,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
            HintText(steps)
        }
    }
}

private fun oemBackgroundSteps(brand: String): String = when {
    brand.contains("oneplus") || brand.contains("oppo") || brand.contains("realme") ->
        "设置 → 应用 → 功耗热测仪 → 耗电管理 → 允许后台活动/自启动"
    brand.contains("xiaomi") || brand.contains("redmi") ->
        "设置 → 应用设置 → 应用管理 → 功耗热测仪 → 省电策略选“无限制”，并允许自启动"
    brand.contains("huawei") || brand.contains("honor") ->
        "设置 → 应用 → 应用启动管理 → 功耗热测仪 → 关闭自动管理，允许后台活动"
    brand.contains("vivo") || brand.contains("iqoo") ->
        "设置 → 电池 → 后台耗电管理 → 功耗热测仪 → 允许后台高耗电"
    else -> "设置 → 应用 → 功耗热测仪 → 电池 → 允许后台活动/不受限制"
}

private fun oemSleepSteps(brand: String): String = when {
    brand.contains("oneplus") || brand.contains("oppo") || brand.contains("realme") ->
        "设置 → 电池 → 更多设置 → 睡眠待机优化 → 关闭（或设为“不优化”）"
    brand.contains("xiaomi") || brand.contains("redmi") ->
        "设置 → 电池 → 省电与电池 → 情景模式/睡眠模式 → 关闭"
    brand.contains("huawei") || brand.contains("honor") ->
        "设置 → 电池 → 更多电池设置 → 休眠时保持网络连接 → 始终"
    brand.contains("vivo") || brand.contains("iqoo") ->
        "设置 → 电池 → 睡眠待机优化 → 关闭"
    else -> "设置 → 电池 → 睡眠待机优化 → 关闭（不同品牌名称可能为“深度睡眠/智能省电”）"
}
