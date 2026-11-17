package com.mobilelab.powertrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import java.util.Locale

/** 竖屏长页面滚动容器（LabScaffold 的 content 默认不滚动）。 */
@Composable
fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(8.dp))
    }
}

/** 底部固定操作区（避开导航栏）。 */
@Composable
fun BottomActions(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabColors.Background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** 权限 / 特殊授权一行：标题 + 状态 + 跳转按钮。 */
@Composable
fun AccessRow(
    title: String,
    granted: Boolean,
    hint: String,
    actionLabel: String,
    onAction: () -> Unit,
    required: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
                Spacer(Modifier.width(6.dp))
                StatusPill(
                    text = if (granted) "已授权" else if (required) "必须" else "可选",
                    tone = when {
                        granted -> PillTone.Success
                        required -> PillTone.Warning
                        else -> PillTone.Muted
                    },
                )
            }
            Text(
                text = hint,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
            )
        }
        if (!granted) {
            LinkButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** 等宽数值块：标签 + 数值 + 单位。 */
@Composable
fun MonoValue(label: String, value: String, unit: String, tone: androidx.compose.ui.graphics.Color = LabColors.TextPrimary) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LabColors.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = tone,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(2.dp))
            Text(unit, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        }
    }
}

fun fmtOrDash(value: Double?, digits: Int = 1): String =
    if (value == null || !value.isFinite()) "--" else String.format(Locale.US, "%.${digits}f", value)

fun intOrDash(value: Int?): String = value?.toString() ?: "--"

/** thermal_status(0..6) 的中文说明。 */
fun thermalLabel(status: Int?): String = when (status) {
    null -> "未知"
    0 -> "NONE 正常"
    1 -> "LIGHT 轻微"
    2 -> "MODERATE 中度"
    3 -> "SEVERE 严重"
    4 -> "CRITICAL 临界"
    5 -> "EMERGENCY 紧急"
    6 -> "SHUTDOWN 关机"
    else -> "状态 $status"
}

fun thermalTone(status: Int?): PillTone = when {
    status == null -> PillTone.Muted
    status <= 1 -> PillTone.Success
    status <= 2 -> PillTone.Info
    status <= 3 -> PillTone.Warning
    else -> PillTone.Error
}

/** 把核心指标排成 2 列卡片网格。 */
@Composable
fun MetricGrid(items: List<Triple<String, String, String>>) {
    items.chunked(2).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItems.forEach { (label, value, unit) ->
                Column(Modifier.weight(1f)) {
                    MonoValue(label = label, value = value, unit = unit)
                }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
