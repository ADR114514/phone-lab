package com.mobilelab.signalmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.service.EnvironmentStatus
import kotlinx.coroutines.delay
import java.util.Locale

/** Transient feedback banner; auto dismisses so it never covers the workflow. */
@Composable
fun MessageBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(5_000)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LabColors.Primary.copy(alpha = 0.14f))
            .border(1.dp, LabColors.Primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        LinkButton(text = "知道了", onClick = onDismiss)
    }
}

/** Red/green checklist row for one environment condition. */
@Composable
fun EnvironmentRow(
    label: String,
    ok: Boolean,
    value: String = "",
    fixLabel: String? = null,
    onFix: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(text = if (ok) "正常" else "异常", tone = if (ok) PillTone.Success else PillTone.Error)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                )
            }
        }
        if (!ok && fixLabel != null && onFix != null) {
            LinkButton(text = fixLabel, onClick = onFix)
        }
    }
}

/** Three-segment progress indicator for a station's 0..3 repeats. */
@Composable
fun ProgressSegments(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    segmentWidth: Dp = 26.dp,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .width(segmentWidth)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (index < done) LabColors.Success else LabColors.SurfaceHigh)
            )
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Horizontally scrollable single-choice chips (types, materials, stations). */
@Composable
fun ChipSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) LabColors.Primary.copy(alpha = 0.16f) else LabColors.SurfaceHigh)
                    .border(
                        1.dp,
                        if (selected) LabColors.Primary else LabColors.Outline,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) LabColors.Primary else LabColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

enum class StepState { Pending, Active, Done }

@Composable
fun StepProgressRow(
    index: Int,
    title: String,
    detail: String,
    state: StepState,
) {
    val color = when (state) {
        StepState.Done -> LabColors.Success
        StepState.Active -> LabColors.Primary
        StepState.Pending -> LabColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state == StepState.Done) "✓" else "$index",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == StepState.Pending) LabColors.TextSecondary else LabColors.TextPrimary,
                fontWeight = if (state == StepState.Active) FontWeight.Medium else FontWeight.Normal,
            )
            if (state == StepState.Active && detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            }
        }
        if (state == StepState.Active) {
            StatusPill(text = "进行中", tone = PillTone.Info)
        }
    }
}

/**
 * Vertical comparison bars. Unlike core's BarSeries this handles negative
 * values (RSSI in dBm) by scaling the visible range instead of the magnitude.
 */
@Composable
fun ComparisonBars(
    items: List<Pair<String, Float>>,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = LabColors.Primary,
) {
    if (items.isEmpty()) {
        Text(
            text = "暂无数据",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
            modifier = Modifier.padding(vertical = 20.dp),
        )
        return
    }
    val values = items.map { it.second }
    val lo = values.min()
    val hi = values.max()
    val span = if (hi - lo < 1e-6f) 1f else hi - lo
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { _, item ->
                val fraction = ((item.second - lo) / span).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", item.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .width(20.dp)
                            .height((16f + 110f * fraction).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(color.copy(alpha = 0.65f))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            text = "单位：$unit（柱高按点位间相对差值缩放）",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Horizontally scrollable monospace table preview of the survey CSV. */
@Composable
fun DataTablePreview(
    header: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
) {
    val widths = remember(header) {
        header.mapIndexed { index, _ -> if (index == 0) 150.dp else 96.dp }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(Modifier.background(LabColors.SurfaceHigh)) {
            header.forEachIndexed { index, name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(widths[index])
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                )
            }
        }
        rows.forEachIndexed { rowIndex, cells ->
            Row(
                Modifier.background(
                    if (rowIndex % 2 == 0) Color.Transparent else LabColors.SurfaceHigh.copy(alpha = 0.35f)
                )
            ) {
                cells.forEachIndexed { index, cell ->
                    Text(
                        text = cell,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = LabColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(widths[index])
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Decimal input that tolerates an empty/partial value. */
@Composable
fun DoubleField(
    value: Double?,
    onValueChange: (Double?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text.filter { it.isDigit() || it == '.' }.take(8)
            onValueChange(draft.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Blocking-environment card reused by the home and measure screens. */
@Composable
fun EnvironmentBlockerCard(
    status: EnvironmentStatus,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    if (status.measurementReady) return
    SectionCard(title = "测量前置条件未满足", accent = LabColors.Error) {
        status.blockingReasons.forEach { reason ->
            Text(
                text = "· $reason",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextPrimary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!status.wifiConnected) {
                LinkButton(text = "打开 Wi-Fi 设置", onClick = onOpenWifiSettings)
            }
            if (!status.locationEnabled) {
                LinkButton(text = "打开定位开关", onClick = onOpenLocationSettings)
            }
            if (!status.locationPermission || (status.nearbyWifiRequired && !status.nearbyWifiGranted)) {
                LinkButton(text = "授予权限", onClick = onRequestPermissions)
            }
            LinkButton(text = "应用设置", onClick = onOpenAppSettings)
        }
        Text(
            text = "环境不满足时禁止开始测量，且不会写入任何数据。",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
