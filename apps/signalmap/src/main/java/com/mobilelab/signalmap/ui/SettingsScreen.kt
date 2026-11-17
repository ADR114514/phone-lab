package com.mobilelab.signalmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.DangerButton
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.NumberField
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.data.AppSettings
import com.mobilelab.signalmap.vm.SurveyViewModel

@Composable
fun SettingsScreen(vm: SurveyViewModel, onBack: () -> Unit) {
    val current = vm.settings
    var httpUrl by remember { mutableStateOf(current.httpTestUrl) }
    var tcpTarget by remember { mutableStateOf(current.tcpTarget) }
    var rttAttempts by remember { mutableStateOf(current.rttAttempts) }
    var throughputEnabled by remember { mutableStateOf(current.throughputEnabled) }
    var hashBssid by remember { mutableStateOf(current.hashBssidOnExport) }
    var confirmReset by remember { mutableStateOf(false) }

    LabScaffold(
        title = "设置",
        subtitle = "测量协议参数（默认值与数据字典 2.3 一致）",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MessageBanner(vm.message) { vm.postMessage(null) }

            SectionCard(title = "网络探测") {
                LabTextField(
                    value = httpUrl,
                    onValueChange = { httpUrl = it },
                    label = "HTTP 测试 URL",
                    placeholder = AppSettings.DEFAULT_HTTP_URL,
                )
                Spacer(Modifier.height(8.dp))
                LabTextField(
                    value = tcpTarget,
                    onValueChange = { tcpTarget = it },
                    label = "TCP 目标（host:port）",
                    placeholder = AppSettings.DEFAULT_TCP_TARGET,
                )
                Spacer(Modifier.height(8.dp))
                NumberField(
                    value = rttAttempts,
                    onValueChange = { rttAttempts = it },
                    label = "每轮 TCP RTT 次数（默认 20）",
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow("单次 TCP 超时", "${AppSettings.DEFAULT_TCP_TIMEOUT_MS} ms（固定，按字典 2.3）")
                KeyValueRow("HTTP 请求次数", "${AppSettings.DEFAULT_HTTP_ATTEMPTS} 次（固定）")
                KeyValueRow("网关 TCP 次数", "${AppSettings.DEFAULT_GATEWAY_ATTEMPTS} 次（固定）")
                KeyValueRow("每点重复次数", "${AppSettings.REPEATS_PER_POINT} 次（固定，8 点位 × 3 = 24 行）")
                KeyValueRow("重复最小间隔", "${AppSettings.REPEAT_INTERVAL_SEC} 秒")
            }

            SectionCard(title = "可选测试") {
                SwitchRow(
                    label = "吞吐测试（512 KB 下载）",
                    hint = "默认关闭；开启后每轮额外下载 512 KB 并记录 down_kbps",
                    checked = throughputEnabled,
                    onCheckedChange = { throughputEnabled = it },
                )
                SwitchRow(
                    label = "导出时哈希 BSSID",
                    hint = "默认开启：导出 ZIP/CSV 时把 BSSID 替换为 sha256 前 16 位（h…），工作目录保留原值",
                    checked = hashBssid,
                    onCheckedChange = { hashBssid = it },
                )
            }

            SectionCard(title = "保存") {
                PrimaryButton(
                    text = "保存设置",
                    onClick = {
                        vm.saveSettings(
                            current.copy(
                                httpTestUrl = httpUrl,
                                tcpTarget = tcpTarget,
                                rttAttempts = rttAttempts,
                                throughputEnabled = throughputEnabled,
                                hashBssidOnExport = hashBssid,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                HintText("修改后对后续测量轮次生效；已写入的 CSV 不会被改写。")
            }

            SectionCard(title = "重置进度", accent = LabColors.Error) {
                HintText("清空 8 个点位 × 3 次的进度并结束当前会话；已写入的实验文件仍保留在实验目录中，便于核对。")
                Spacer(Modifier.height(8.dp))
                DangerButton(
                    text = "重置进度并结束当前会话",
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("确认重置进度") },
            text = {
                Text("将清空当前会话进度（不可撤销，但已写入磁盘的实验文件不会被删除）。确定继续？")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetProgress()
                    confirmReset = false
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
            containerColor = LabColors.Surface,
            titleContentColor = LabColors.TextPrimary,
            textContentColor = LabColors.TextPrimary,
        )
    }
}