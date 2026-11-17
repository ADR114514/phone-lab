package com.mobilelab.vibrodiag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.model.Conditions
import java.util.Locale
import kotlin.math.abs

/** Localised number helpers used across the screens. */
object Fmt {
    fun value(value: Double, digits: Int = 2): String =
        if (!value.isFinite()) "--" else String.format(Locale.US, "%." + digits + "f", value)

    fun hz(value: Double): String = when {
        !value.isFinite() -> "--"
        value >= 1000.0 -> String.format(Locale.US, "%.2f kHz", value / 1000.0)
        else -> String.format(Locale.US, "%.1f Hz", value)
    }

    fun dbfs(value: Double): String = if (!value.isFinite()) "--" else String.format(Locale.US, "%.1f dBFS", value)

    fun percent(fraction: Double): String =
        if (!fraction.isFinite()) "--" else String.format(Locale.US, "%.1f%%", fraction * 100.0)

    fun seconds(value: Double): String = if (!value.isFinite()) "--" else String.format(Locale.US, "%.1f s", value)

    fun signedDb(value: Double): String = if (!value.isFinite()) "--" else String.format(Locale.US, "%+.1f dB", value)
}

/** Scrollable body helper: keeps the top bar fixed and pads above the navigation bar. */
@Composable
fun ColumnScope.ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Horizontally scrollable chip selector used for the five conditions. */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
                    .border(1.dp, if (selected) LabColors.Primary else LabColors.Outline, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
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

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LabColors.Surface,
        title = { Text(title, color = LabColors.TextPrimary) },
        text = { Text(text, color = LabColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) LabColors.Error else LabColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = LabColors.TextSecondary) }
        },
    )
}

/** Read-only multi line dialog used to inspect meta.json / evaluation JSON. */
@Composable
fun TextContentDialog(title: String, content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LabColors.Surface,
        title = { Text(title, color = LabColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = content.ifBlank { "(空)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = LabColors.Primary) }
        },
    )
}

/** Confusion matrix table; rows are the true label, columns the predicted label. */
@Composable
fun ConfusionMatrixTable(labels: List<String>, matrix: List<List<Int>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = "真\\预测",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
                modifier = Modifier.width(64.dp),
            )
            labels.forEach { label ->
                Text(
                    text = Conditions.shortLabelOf(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        matrix.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = Conditions.shortLabelOf(labels.getOrElse(rowIndex) { "" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                    modifier = Modifier.width(64.dp),
                )
                row.forEachIndexed { columnIndex, count ->
                    val color = when {
                        columnIndex != rowIndex -> LabColors.TextSecondary
                        count > 0 -> LabColors.Success
                        else -> LabColors.TextSecondary
                    }
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Compact alignment row used above the confidence/prediction blocks. */
@Composable
fun MetricPairRow(label: String, value: String, accent: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = accent ?: LabColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Small helper for confidence percentages with a sign-free absolute display. */
fun confidencePercent(value: Double): String =
    if (!value.isFinite()) "--" else String.format(Locale.US, "%.1f%%", abs(value) * 100.0)
