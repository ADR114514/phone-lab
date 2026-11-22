package com.mobilelab.chargeaudit.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mobilelab.chargeaudit.data.AuditState
import com.mobilelab.chargeaudit.data.BatteryReader
import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.Nameplate
import com.mobilelab.chargeaudit.model.Screen
import com.mobilelab.chargeaudit.service.Reminder
import com.mobilelab.core.files.FileExporter
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.time.TimeFmt
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
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun ChargeAuditApp(state: AuditState) {
    BackHandler(enabled = state.screen != Screen.HOME) { state.screen = Screen.HOME }
    when (state.screen) {
        Screen.HOME -> HomeScreen(state)
        Screen.RECORD -> RecordScreen(state)
        Screen.RESULTS -> ResultsScreen(state)
        Screen.SETTINGS -> SettingsScreen(state)
    }
}

@Composable
private fun ScreenScroll(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun MessageBanner(text: String?, onDismiss: () -> Unit) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.Primary,
            modifier = Modifier.weight(1f),
        )
        LinkButton("知道了", onDismiss)
    }
}

@Composable
private fun HomeScreen(state: AuditState) {
    val context = LocalContext.current
    val summary = state.summary()
    LabScaffold(
        title = "充电核验仪",
        subtitle = "课题07 · 充电宝标称容量与转换效率核验",
        status = if (state.active) "采集中 · " + state.points.size + " 点" else "10 min / 点",
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            NextStepCard(state)
            NameplateCard(state, onEdit = { state.screen = Screen.SETTINGS })
            if (summary != null) {
                SectionCard(title = "当前会话概览") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("输出能量", ChargeMath.fmt(summary.eOutWh, 2), "Wh", modifier = Modifier.weight(1f))
                        MetricTile("手机获得", ChargeMath.fmt(summary.ePhoneWh, 2), "Wh", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("整体效率", ChargeMath.fmt(summary.etaTotal * 100, 1), "%", modifier = Modifier.weight(1f))
                        MetricTile("输出/标称", ChargeMath.fmt(summary.etaOutput * 100, 1), "%", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("查看完整结果", { state.screen = Screen.RESULTS }, Modifier.fillMaxWidth())
                }
            }
            SectionCard(title = "实验流程") {
                HintText("1. 充电宝充满，手机固定到约 20% 电量；手机熄屏、关 Wi-Fi/蓝牙/移动数据。")
                HintText("2. USB 检测仪接在充电宝与手机之间；每 10 min 记录电压、电流与手机电量。")
                HintText("3. 手机到 100% 继续记录，直到充电宝自动关机；至少重复 2 次。")
                Spacer(Modifier.height(6.dp))
                HintText("提示：检测仪读数要手动输入 App；手机电量/温度/电荷计由 App 自动读取。")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("设置", { state.screen = Screen.SETTINGS }, Modifier.weight(1f))
                SecondaryButton("结果与导出", { state.screen = Screen.RESULTS }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NextStepCard(state: AuditState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active, state.nextDueMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    SectionCard(title = "下一步", accent = LabColors.Primary) {
        if (!state.active) {
            StatusPill("步骤 1 / 3 · 开始会话", PillTone.Info)
            Spacer(Modifier.height(8.dp))
            Text("准备开始一次完整放电", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
            HintText("先充满充电宝、手机固定 20% 电量，再把 USB 检测仪串在中间。")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("开始新会话", { state.startSession() }, Modifier.fillMaxWidth())
        } else {
            StatusPill("步骤 2 / 3 · 记录中", PillTone.Success)
            Spacer(Modifier.height(8.dp))
            val remainingMs = state.nextDueMs - now
            val countdown = if (remainingMs > 0) {
                val minutes = remainingMs / 60000
                val seconds = (remainingMs % 60000) / 1000
                "下次记录还有 " + minutes + " 分 " + seconds + " 秒"
            } else {
                "已到记录时间，录入后自动续 10 分钟"
            }
            Text(
                text = "第 " + (state.points.size + 1) + " 个采样点",
                style = MaterialTheme.typography.titleMedium,
                color = LabColors.TextPrimary,
            )
            HintText(countdown + " · 已记录 " + state.points.size + " 点")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("继续记录本点", { state.screen = Screen.RECORD }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("结束会话", { state.finishSession() }, Modifier.weight(1f))
                SecondaryButton("结果与导出", { state.screen = Screen.RESULTS }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NameplateCard(state: AuditState, onEdit: () -> Unit) {
    val plate = state.nameplate
    SectionCard(title = "充电宝铭牌") {
        KeyValueRow("型号", plate.model)
        KeyValueRow("额定能量", ChargeMath.fmt(plate.ratedEnergyWh, 2) + " Wh")
        KeyValueRow("额定输出容量", ChargeMath.fmt(plate.ratedOutputMah, 0) + " mAh @" + ChargeMath.fmt(plate.ratedOutputV, 1) + " V")
        KeyValueRow("铭牌反推升压效率", ChargeMath.fmt(plate.nameplateBoostEta * 100, 1) + " %")
        KeyValueRow("手机电池", ChargeMath.fmt(plate.phoneCapacityMah, 0) + " mAh / " + ChargeMath.fmt(plate.phoneNominalV, 2) + " V")
        Spacer(Modifier.height(6.dp))
        LinkButton("编辑铭牌参数", onEdit)
    }
}

@Composable
private fun RecordScreen(state: AuditState) {
    val context = LocalContext.current
    var voltageText by remember { mutableStateOf(state.points.lastOrNull()?.usbVoltageV?.let { String.format(Locale.US, "%.2f", it) } ?: "5.00") }
    var currentText by remember { mutableStateOf(state.points.lastOrNull()?.usbCurrentA?.let { String.format(Locale.US, "%.2f", it) } ?: "1.80") }
    var testerText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(BatteryReader.read(context)) }
    var showDeleteLast by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active, state.nextDueMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = state.nextDueMs - now
    LabScaffold(
        title = "记录本点",
        subtitle = state.sessionId ?: "未开始会话",
        status = if (state.active) "第 " + (state.points.size + 1) + " 点" else "已结束",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            SectionCard(title = "进度") {
                KeyValueRow("已记录", state.points.size.toString() + " 点")
                KeyValueRow("时长", ChargeMath.fmt((now - state.startMs) / 3600000.0, 2) + " h")
                KeyValueRow(
                    "下次提醒",
                    if (remaining > 0) {
                        (remaining / 60000).toString() + " 分 " + ((remaining % 60000) / 1000) + " 秒后"
                    } else {
                        "已到点"
                    },
                )
                HintText("每 10 分钟记录一次；提前/迟到记录均可，实际间隔会写进 CSV。")
            }
            SectionCard(title = "手机当前状态（自动读取）") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("电量", battery.pct?.let { it.toString() } ?: "--", "%", modifier = Modifier.weight(1f))
                    MetricTile("电池温度", ChargeMath.fmt(battery.tempC, 1), "°C", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("电压", battery.voltageMv?.toString() ?: "--", "mV", modifier = Modifier.weight(1f))
                    MetricTile("电荷计", battery.chargeUah?.toString() ?: "--", "µAh", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if (battery.charging) "正在充电" else "未充电/已满", if (battery.charging) PillTone.Success else PillTone.Warning)
                    Spacer(Modifier.width(8.dp))
                    LinkButton("刷新", { battery = BatteryReader.read(context) })
                }
                HintText("手机到 100% 后仍要继续记录，直到充电宝自动关机。")
            }
            SectionCard(title = "录入检测仪读数", accent = LabColors.Primary) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("USB 电压 V", voltageText, { voltageText = it }, Modifier.weight(1f))
                    NumberField("USB 电流 A", currentText, { currentText = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                NumberField(
                    "检测仪累计能量 Wh（可选，若你的表有）",
                    testerText,
                    { testerText = it },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                val voltage = voltageText.replace(",", ".").toDoubleOrNull()
                val current = currentText.replace(",", ".").toDoubleOrNull()
                PrimaryButton(
                    text = "保存第 " + (state.points.size + 1) + " 点",
                    onClick = {
                        if (voltage == null || current == null) {
                            state.message = "电压/电流格式不正确"
                            return@PrimaryButton
                        }
                        val latestBattery = BatteryReader.read(context)
                        battery = latestBattery
                        val error = state.addPoint(
                            usbV = voltage,
                            usbA = current,
                            testerWh = testerText.replace(",", ".").toDoubleOrNull(),
                            note = noteText.trim(),
                            battery = latestBattery,
                        )
                        if (error != null) state.message = error else noteText = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.active,
                )
                HintText("保存后自动安排下一次 10 min 提醒；如果系统限制后台，请保持通知权限开启。")
            }
            SectionCard(title = "最近记录") {
                val recent = state.points.takeLast(5).reversed()
                if (recent.isEmpty()) {
                    EmptyState("还没有采样点", "填好电压/电流后保存第 1 点")
                } else {
                    recent.forEach { point -> PointRow(point) }
                    Spacer(Modifier.height(6.dp))
                    LinkButton("删除最后一个点", { showDeleteLast = true })
                }
            }
            SecondaryButton("结束会话并计算效率", { showFinish = true }, Modifier.fillMaxWidth(), enabled = state.active)
        }
    }

    if (showDeleteLast) {
        AlertDialog(
            onDismissRequest = { showDeleteLast = false },
            title = { Text("删除最后一个采样点？") },
            text = { Text("会同步重写 CSV；删除后不影响已有会话，其他点保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteLast = false
                    state.deleteLastPoint()
                }) { Text("删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteLast = false }) { Text("取消") } },
        )
    }
    if (showFinish) {
        AlertDialog(
            onDismissRequest = { showFinish = false },
            title = { Text("结束会话？") },
            text = { Text("结束后停止提醒，进入结果页；之后仍可导出数据或删除会话。") },
            confirmButton = {
                TextButton(onClick = {
                    showFinish = false
                    state.finishSession()
                }) { Text("结束") }
            },
            dismissButton = { TextButton(onClick = { showFinish = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun PointRow(point: AuditPoint) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#" + (point.index + 1), style = MaterialTheme.typography.labelMedium, color = LabColors.Primary, modifier = Modifier.width(32.dp))
        Text(
            ChargeMath.fmt(point.usbVoltageV, 2) + " V · " + ChargeMath.fmt(point.usbCurrentA, 2) + " A · " + ChargeMath.fmt(point.powerW, 2) + " W",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            point.phonePct?.let { it.toString() + "%" } ?: "--",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
        )
    }
}

@Composable
private fun ResultsScreen(state: AuditState) {
    val context = LocalContext.current
    val exporter = rememberFileExporter()
    val summary = state.summary()
    var message by remember { mutableStateOf<String?>(null) }
    LabScaffold(
        title = "结果与导出",
        subtitle = state.sessionId ?: "未开始会话",
        status = state.points.size.toString() + " 点",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(message) { message = null }
            if (summary == null) {
                SectionCard(title = "数据不足") {
                    EmptyState("至少需要 2 个采样点", "继续记录后再查看效率")
                }
            } else {
                SectionCard(title = "效率汇总", accent = LabColors.Primary) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("输出能量", ChargeMath.fmt(summary.eOutWh, 3), "Wh", modifier = Modifier.weight(1f))
                        MetricTile("手机获得", ChargeMath.fmt(summary.ePhoneWh, 3), "Wh", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("输出/标称", ChargeMath.fmt(summary.etaOutput * 100, 1), "%", modifier = Modifier.weight(1f))
                        MetricTile("整体效率", ChargeMath.fmt(summary.etaTotal * 100, 1), "%", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("时长", ChargeMath.fmt(summary.durationH, 2) + " h")
                    KeyValueRow("平均/峰值功率", ChargeMath.fmt(summary.avgPowerW, 2) + " W / " + ChargeMath.fmt(summary.peakPowerW, 2) + " W")
                    KeyValueRow("手机获得能量口径", summary.phoneEnergyMethod)
                    KeyValueRow("等效输出容量", ChargeMath.fmt(summary.equivOutputMah, 0) + " mAh @" + ChargeMath.fmt(state.nameplate.ratedOutputV, 1) + " V")
                    KeyValueRow("手机电量", (summary.startPct ?: 0).toString() + "% → " + (summary.endPct ?: 0).toString() + "%")
                    KeyValueRow("铭牌反推升压效率", ChargeMath.fmt(summary.nameplateBoostEta * 100, 1) + " %")
                }
                SectionCard(title = "损耗分解") {
                    KeyValueRow("电芯 → 输出", ChargeMath.fmt(summary.lossBoostWh, 3) + " Wh (" + ChargeMath.fmt(summary.lossBoostPct, 1) + "%)")
                    KeyValueRow("输出 → 手机电池", ChargeMath.fmt(summary.lossChargeWh, 3) + " Wh (" + ChargeMath.fmt(summary.lossChargePct, 1) + "%)")
                    KeyValueRow("合计", ChargeMath.fmt(summary.lossTotalWh, 3) + " Wh (" + ChargeMath.fmt(summary.lossTotalPct, 1) + "%)")
                    HintText("输出能量用梯形积分；手机获得能量优先用电荷计差分，没有电荷计时用电量% × 手机电池容量。")
                }
                SectionCard(title = "导出") {
                    HintText("ZIP 包含 CSV 原始表、manifest.json（铭牌参数）与 summary.json（效率/损耗）。")
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("生成并分享 ZIP", {
                        val zip = state.exportZip()
                        if (zip == null) {
                            message = "没有可导出的会话数据"
                        } else {
                            exporter.share(zip, "application/zip")
                            message = "已生成 " + zip.name
                        }
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("保存 ZIP", {
                            val zip = state.exportZip()
                            if (zip != null) exporter.saveAs(zip, "application/zip") else message = "没有可导出的数据"
                        }, Modifier.weight(1f))
                        SecondaryButton("分享 CSV", {
                            val csv = state.csvFile()
                            if (csv != null) exporter.share(csv, "text/csv") else message = "没有 CSV"
                        }, Modifier.weight(1f))
                    }
                }
            }
            SectionCard(title = "原始表预览") {
                if (state.points.isEmpty()) {
                    EmptyState("暂无数据", "先记录采样点")
                } else {
                    state.points.takeLast(12).forEach { point -> PointRow(point) }
                    HintText("完整表见 CSV/导出 ZIP；字段与 data/templates/topic07/charge_discharge_template.csv 一致。")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AuditState) {
    val context = LocalContext.current
    var model by remember { mutableStateOf(state.nameplate.model) }
    var ratedWh by remember { mutableStateOf(state.nameplate.ratedEnergyWh.toString()) }
    var ratedMah by remember { mutableStateOf(state.nameplate.ratedOutputMah.toString()) }
    var ratedV by remember { mutableStateOf(state.nameplate.ratedOutputV.toString()) }
    var phoneMah by remember { mutableStateOf(state.nameplate.phoneCapacityMah.toString()) }
    var phoneV by remember { mutableStateOf(state.nameplate.phoneNominalV.toString()) }
    var tester by remember { mutableStateOf(state.nameplate.testerModel) }
    var showDelete by remember { mutableStateOf(false) }
    LabScaffold(
        title = "设置",
        subtitle = "铭牌参数、提醒与数据管理",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            SectionCard(title = "充电宝 / 手机参数") {
                OutlinedTextField(model, { model = it }, label = { Text("充电宝型号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("额定能量 Wh", ratedWh, { ratedWh = it }, Modifier.weight(1f))
                    NumberField("额定输出 mAh", ratedMah, { ratedMah = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("额定输出 V", ratedV, { ratedV = it }, Modifier.weight(1f))
                    NumberField("手机电池 mAh", phoneMah, { phoneMah = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("手机标称 V", phoneV, { phoneV = it }, Modifier.weight(1f))
                    NumberField("测试仪型号", tester, { tester = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存参数", {
                    state.saveNameplate(
                        Nameplate(
                            model = model.trim().ifBlank { "未命名充电宝" },
                            ratedEnergyWh = ratedWh.toDoubleOrNull() ?: state.nameplate.ratedEnergyWh,
                            ratedOutputMah = ratedMah.toDoubleOrNull() ?: state.nameplate.ratedOutputMah,
                            ratedOutputV = ratedV.toDoubleOrNull() ?: state.nameplate.ratedOutputV,
                            phoneCapacityMah = phoneMah.toDoubleOrNull() ?: state.nameplate.phoneCapacityMah,
                            phoneNominalV = phoneV.toDoubleOrNull() ?: state.nameplate.phoneNominalV,
                            testerModel = tester.trim(),
                        ),
                    )
                }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "提醒与权限") {
                HintText("每次保存后，App 会安排 10 分钟后的提醒；ColorOS 可能延迟后台闹钟，建议允许通知/精确闹钟。")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("通知设置", { openNotificationSettings(context) }, Modifier.weight(1f))
                    SecondaryButton("精确闹钟", { openExactAlarmSettings(context) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("发送一条测试提醒", { Reminder.postNow(context) }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "数据管理", accent = LabColors.Error) {
                HintText("删除当前会话会移除 CSV/manifest 并清空状态；不可恢复，建议先导出 ZIP。")
                Spacer(Modifier.height(8.dp))
                SecondaryButton("删除本会话数据", { showDelete = true }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "关于") {
                HintText("充电核验仪 · 课题07：充电宝标称容量与转换效率核验")
                HintText("数据目录：Android/data/com.mobilelab.chargeaudit/files/experiments/chargeaudit/")
                HintText("配套分析脚本：analysis/charge/analyze.py（仓库）")
                HintText("版本 " + com.mobilelab.chargeaudit.BuildConfig.VERSION_NAME)
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除本会话数据？") },
            text = { Text("将删除 CSV、manifest 与当前进度，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    state.resetSession(true)
                }) { Text("删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(("package:" + context.packageName).toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
