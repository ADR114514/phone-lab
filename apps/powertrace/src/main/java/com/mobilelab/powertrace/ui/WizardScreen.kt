package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilelab.core.permission.rememberPermissionRequester
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SystemAccess
import com.mobilelab.powertrace.service.SamplingControl
import kotlinx.coroutines.launch

/**
 * 批次向导：实验备注 → 工况顺序 → 开始前检查（权限/环境）→ 开始采集。
 * 顺序决定 block A/B/C/D，用于抵消顺序与温度漂移效应。
 */
@Composable
fun WizardScreen(
    access: SystemAccess.Snapshot,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notes by rememberSaveable { mutableStateOf("") }
    var orderCsv by rememberSaveable { mutableStateOf(ConditionId.defaultOrder.joinToString(",") { it.id }) }
    var starting by remember { mutableStateOf(false) }
    var showStartConfirm by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }

    val order = orderCsv.split(",").mapNotNull { ConditionId.of(it) }
        .ifEmpty { ConditionId.defaultOrder }
    val rawSeries = remember { PowerTraceController.isRawSeriesEnabled() }

    val permissions = remember { SystemAccess.runtimePermissions() }
    val permissionRequester = rememberPermissionRequester(*permissions.toTypedArray())

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in order.indices) return
        val list = order.toMutableList()
        val tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        orderCsv = list.joinToString(",") { it.id }
    }

    fun start() {
        if (starting) return
        starting = true
        startError = null
        scope.launch {
            runCatching {
                PowerTraceController.createCampaign(
                    notes = notes,
                    order = order,
                    rawSeriesEnabled = rawSeries,
                )
            }.onSuccess {
                SamplingControl.start(context)
                starting = false
                onStarted()
            }.onFailure {
                starting = false
                startError = "创建批次失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    LabScaffold(
        title = "新建批次",
        subtitle = "配置实验参数并完成开始前检查",
        onBack = onBack,
        bottomBar = {
            BottomActions {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(text = "返回", onClick = onBack, enabled = !starting)
                    PrimaryButton(
                        text = "开始采集（${order.size * 30} min）",
                        onClick = {
                            if (access.charging == true || access.warnings.isNotEmpty()) {
                                showStartConfirm = true
                            } else {
                                start()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = starting,
                    )
                }
            }
        },
    ) {
        ScreenScroll {
            SectionCard(title = "实验备注") {
                LabTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "备注（写入每行 note，可留空）",
                    placeholder = "例如：游戏为 XXX 60fps 模式，实验室 26°C",
                )
                Spacer(Modifier.height(6.dp))
                HintText("备注会与自动标记（late_sample / charging_during_sample 等）一起写入 CSV 的 note 列。")
            }

            SectionCard(title = "工况顺序（决定 block A/B/C/D）") {
                order.forEachIndexed { index, condition ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill(text = ConditionId.blockLetter(index).toString(), tone = PillTone.Info)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = condition.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LabColors.TextPrimary,
                            )
                            Text(
                                text = condition.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextSecondary,
                            )
                        }
                        LinkButton(text = "上移", onClick = { move(index, -1) })
                        LinkButton(text = "下移", onClick = { move(index, +1) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    "总时长：${order.size} × 30 min = ${order.size * 30} min（${order.size} × ${SamplingConfig.SAMPLES_PER_CONDITION} 个采样点）",
                )
            }

            SectionCard(title = "开始前检查：授权") {
                AccessRow(
                    title = "通知权限",
                    granted = access.notifications,
                    hint = "前台服务进度显示在通知栏，拒绝后仍可采集但看不到进度",
                    actionLabel = "授权",
                    onAction = { permissionRequester.request() },
                )
                AccessRow(
                    title = "电池优化豁免",
                    granted = access.batteryUnrestricted,
                    hint = "未豁免时 Doze 可能延迟采样，待机工况误差增大",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestBatteryExemption(context) },
                    required = true,
                )
                AccessRow(
                    title = "精确闹钟",
                    granted = access.exactAlarm,
                    hint = "用于熄屏待机时准时唤醒采样（无权限则退化为不精确闹钟）",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestExactAlarmAccess(context) },
                    required = true,
                )
                AccessRow(
                    title = "使用情况访问",
                    granted = access.usageStats,
                    hint = "可选：用于记录前台应用包名，未授权时 foreground_pkg 写空",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.openUsageAccessSettings(context) },
                )
            }

            SectionCard(title = "开始前检查：环境") {
                StatusLineRow("充电状态", if (access.charging == true) "正在充电（异常）" else "未充电", access.charging != true)
                StatusLineRow("屏幕亮度", if (access.autoBrightness) "自动亮度（会变化）" else "固定亮度", !access.autoBrightness)
                StatusLineRow("屏幕状态", if (access.screenOn) "亮屏" else "熄屏", true)
                StatusLineRow(
                    "定位开关",
                    if (access.locationEnabled) "已开启" else "已关闭（导航拍摄工况需要）",
                    access.locationEnabled,
                )
                StatusLineRow("当前网络", access.network, access.network != "无网络")
                Spacer(Modifier.height(6.dp))
                HintText(
                    "待机工况需要熄屏静置；其余三种工况需保持屏幕常亮。开始后按对应工况的操作提示执行，" +
                        "实验期间不要充电、不要移动设备。",
                )
                if (access.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    access.warnings.forEach { warning ->
                        Text(
                            text = "· $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabColors.Tertiary,
                        )
                    }
                }
            }

            SectionCard(title = "将写入的文件") {
                HintText("manifest.json · session_<工况>.csv（每工况 6 行）· campaign_<批次>.csv（24 行）")
                HintText("campaign_summary_<批次>.csv · session.log（不可用字段原因记录）")
                HintText("原始 5 s 序列：${if (rawSeries) "已开启" else "关闭（可在设置中开启）"}")
            }

            if (startError != null) {
                Text(text = startError!!, style = MaterialTheme.typography.bodySmall, color = LabColors.Error)
            }
        }
    }

    if (showStartConfirm) {
        AlertDialog(
            onDismissRequest = { showStartConfirm = false },
            title = { Text("确认开始采集？") },
            text = {
                Column {
                    Text("检测到以下情况可能影响测量质量：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    (if (access.charging == true) listOf("设备正在充电") else emptyList()).forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall, color = LabColors.Tertiary)
                    }
                    access.warnings.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall, color = LabColors.Tertiary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("继续开始仍会真实记录，异常情况会写入 note 列。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartConfirm = false
                    start()
                }) { Text("仍要开始") }
            },
            dismissButton = {
                TextButton(onClick = { showStartConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusLineRow(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
        }
        StatusPill(text = if (ok) "OK" else "注意", tone = if (ok) PillTone.Success else PillTone.Warning)
    }
}
