package com.mobilelab.vibrodiag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.DangerButton
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.NumberField
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.SelectorRow
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.model.AccelMode
import com.mobilelab.vibrodiag.model.AppSettings
import com.mobilelab.vibrodiag.model.Conditions
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState) {
    var durationS by rememberSaveable { mutableIntStateOf(state.settings.durationS) }
    var accelModeId by rememberSaveable { mutableStateOf(state.settings.accelMode.id) }
    var noiseTypeId by rememberSaveable { mutableStateOf(state.settings.defaultNoiseType.id) }
    var snrDb by rememberSaveable { mutableIntStateOf(state.settings.defaultSnrDb) }
    var placement by rememberSaveable { mutableStateOf(state.settings.placement) }
    var distanceCm by rememberSaveable { mutableIntStateOf(state.settings.distanceCm) }
    var noiseCondition by rememberSaveable { mutableStateOf(state.settings.noiseCondition) }
    var operator by rememberSaveable { mutableStateOf(state.settings.operator) }
    var showNewSessionDialog by remember { mutableStateOf(false) }

    val accelModes = AccelMode.values().toList()
    val snrLevels = listOf(20, 10, 5, 0, -5)

    LabScaffold(
        title = "设置",
        subtitle = "采集参数与实验信息",
        onBack = { state.screen = Screen.Home },
    ) {
        ScrollColumn {
            SectionCard(title = "录音参数") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "录音时长",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        durationS.toString() + " s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.Primary,
                    )
                }
                Slider(
                    value = durationS.toFloat(),
                    onValueChange = { durationS = it.roundToInt().coerceIn(AppSettings.MIN_DURATION_S, AppSettings.MAX_DURATION_S) },
                    valueRange = AppSettings.MIN_DURATION_S.toFloat()..AppSettings.MAX_DURATION_S.toFloat(),
                    steps = AppSettings.MAX_DURATION_S - AppSettings.MIN_DURATION_S - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText("默认 15 s，可在 5–30 s 调整；采样率固定 44.1 kHz 单声道 PCM16。")
                Spacer(Modifier.height(10.dp))
                Text("加速度采样率", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                SelectorRow(
                    options = accelModes.map { it.label },
                    selectedIndex = accelModes.indexOfFirst { it.id == accelModeId }.coerceAtLeast(0),
                    onSelect = { accelModeId = accelModes[it].id },
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    "自动模式优先 200 Hz（需 HIGH_SAMPLING_RATE_SENSORS），否则回退到传感器最小周期；实际频率记录在 meta.json。",
                )
            }

            SectionCard(title = "抗噪评估默认参数") {
                Text("默认噪声类型", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                SelectorRow(
                    options = NoiseType.values().map { it.label },
                    selectedIndex = NoiseType.values().indexOfFirst { it.id == noiseTypeId }.coerceAtLeast(0),
                    onSelect = { noiseTypeId = NoiseType.values()[it].id },
                )
                Spacer(Modifier.height(10.dp))
                Text("默认 SNR", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = snrLevels.map { it.toString() + " dB" },
                    selectedIndex = snrLevels.indexOf(snrDb).coerceAtLeast(0),
                    onSelect = { snrDb = snrLevels[it] },
                )
                HintText("评估页会同时跑白噪声与粉红噪声的 5 个 SNR 档位。")
            }

            SectionCard(title = "实验信息（写入 meta.json）") {
                LabTextField(
                    value = placement,
                    onValueChange = { placement = it },
                    label = "摆放说明 placement",
                    placeholder = "手机平放于风扇底座正前方 30cm 桌面",
                )
                Spacer(Modifier.height(10.dp))
                NumberField(
                    value = distanceCm,
                    onValueChange = { distanceCm = it },
                    label = "设备距离 device_distance_cm",
                )
                Spacer(Modifier.height(10.dp))
                LabTextField(
                    value = noiseCondition,
                    onValueChange = { noiseCondition = it },
                    label = "环境噪声 noise_condition",
                    placeholder = "quiet / fan-only / classroom",
                )
                Spacer(Modifier.height(10.dp))
                LabTextField(
                    value = operator,
                    onValueChange = { operator = it },
                    label = "记录人 operator",
                )
            }

            PrimaryButton(
                "保存设置",
                {
                    state.updateSettings(
                        AppSettings(
                            durationS = durationS,
                            accelMode = AccelMode.fromId(accelModeId),
                            defaultNoiseType = NoiseType.fromId(noiseTypeId) ?: NoiseType.WHITE,
                            defaultSnrDb = snrDb,
                            placement = placement,
                            distanceCm = distanceCm,
                            noiseCondition = noiseCondition,
                            operator = operator,
                        )
                    )
                    state.showMessage("设置已保存")
                },
                Modifier.fillMaxWidth(),
            )

            SectionCard(title = "会话管理") {
                KeyValueRow("当前会话", state.repository.sessionId)
                KeyValueRow("录音组数", state.samples.size.toString())
                Text(
                    text = state.repository.root.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(10.dp))
                DangerButton("新建实验会话", { showNewSessionDialog = true }, Modifier.fillMaxWidth())
                HintText("新建会话会切换到新的空目录；旧会话数据仍保留在本机，可随后通过数据集页或导出 ZIP 找回。")
            }

            SectionCard(title = "关于与隐私") {
                KeyValueRow("App", "声振诊断仪 VibroDiag")
                KeyValueRow("版本", com.mobilelab.vibrodiag.BuildConfig.VERSION_NAME)
                KeyValueRow("数据契约", "phonelab.vibro/1")
                KeyValueRow("项目编号", "03-vibro-diag")
                KeyValueRow("工况数量", Conditions.all.size.toString())
                KeyValueRow("每组目标", Conditions.TARGET_GROUPS.toString())
                Spacer(Modifier.height(8.dp))
                HintText("所有音频、加速度与评估数据仅保存在本机 App 私有目录，不会上传服务器；卸载 App 会删除全部数据。")
            }
        }
    }

    if (showNewSessionDialog) {
        ConfirmDialog(
            title = "新建实验会话",
            text = "当前会话 " + state.repository.sessionId + " 将停止写入，并创建一个新的空会话。确定继续？",
            confirmLabel = "新建",
            danger = true,
            onConfirm = {
                showNewSessionDialog = false
                state.startNewSession()
            },
            onDismiss = { showNewSessionDialog = false },
        )
    }
}
