package com.mobilelab.signalmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.DangerButton
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.NumberField
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.data.PointType
import com.mobilelab.signalmap.data.SurveyPoint
import com.mobilelab.signalmap.data.WallMaterial
import com.mobilelab.signalmap.vm.SurveyViewModel

@Composable
fun PointsScreen(vm: SurveyViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<SurveyPoint?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LabScaffold(
        title = "点位列表",
        subtitle = "8 个内置点位，可编辑名称/类型/墙体材质/遮挡/距离/楼层",
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
            HintText("point_id 固定为 P01..P08，分析脚本按 id 聚合；编辑只影响后续测量行。")

            vm.points.forEach { point ->
                val done = vm.repeatsFor(point.id)
                SectionCard(title = "${point.id} · ${point.name}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = "已完成 $done/3 次",
                            tone = if (done >= 3) PillTone.Success else PillTone.Muted,
                        )
                        Spacer(Modifier.weight(1f))
                        LinkButton(text = "编辑", onClick = { editing = point })
                    }
                    Spacer(Modifier.height(4.dp))
                    LabeledValue("点位类型", "${point.type.label}（${point.type.id}）")
                    LabeledValue("墙体材质", "${point.wallMaterial.label}（${point.wallMaterial.id}）")
                    LabeledValue("遮挡描述", point.obstruction)
                    LabeledValue("估计距离", point.distanceM?.let { "${it.fmt()} m" } ?: "--")
                    LabeledValue("楼层", point.floor?.toString() ?: "--")
                    LabeledValue("照片", point.photoFile.ifEmpty { "未拍摄（测量时 note 记录未拍照）" })
                }
            }

            DangerButton(
                text = "恢复 8 个内置点位预设",
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    editing?.let { point ->
        PointEditDialog(
            point = point,
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.updatePoint(updated)
                editing = null
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复预设") },
            text = { Text("将把 8 个点位的名称、类型、墙体材质、遮挡描述、距离与楼层恢复为课程预设值（照片引用保留）。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetPointsToPreset()
                    confirmReset = false
                }) { Text("恢复") }
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

@Composable
internal fun LabeledValue(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Text(
            text = value.ifEmpty { "--" },
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PointEditDialog(
    point: SurveyPoint,
    onDismiss: () -> Unit,
    onSave: (SurveyPoint) -> Unit,
) {
    var name by remember(point.id) { mutableStateOf(point.name) }
    var type by remember(point.id) { mutableStateOf(point.type) }
    var wall by remember(point.id) { mutableStateOf(point.wallMaterial) }
    var obstruction by remember(point.id) { mutableStateOf(point.obstruction) }
    var distance by remember(point.id) { mutableStateOf(point.distanceM) }
    var floor by remember(point.id) { mutableStateOf(point.floor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑点位 ${point.id}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabTextField(value = name, onValueChange = { name = it }, label = "点位名称")
                Text("点位类型", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                ChipSelector(
                    options = PointType.entries.map { it.label },
                    selectedIndex = PointType.entries.indexOf(type),
                    onSelect = { type = PointType.entries[it] },
                )
                Text("墙体材质", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                ChipSelector(
                    options = WallMaterial.entries.map { it.label },
                    selectedIndex = WallMaterial.entries.indexOf(wall),
                    onSelect = { wall = WallMaterial.entries[it] },
                )
                LabTextField(
                    value = obstruction,
                    onValueChange = { obstruction = it },
                    label = "遮挡描述",
                    singleLine = false,
                )
                DoubleField(
                    value = distance,
                    onValueChange = { distance = it },
                    label = "到最近 AP 估计距离 (m)",
                )
                NumberField(
                    value = floor ?: 0,
                    onValueChange = { floor = it.takeIf { value -> value > 0 } },
                    label = "楼层",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        point.copy(
                            name = name.trim().ifEmpty { point.name },
                            type = type,
                            wallMaterial = wall,
                            obstruction = obstruction.trim(),
                            distanceM = distance,
                            floor = floor,
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = LabColors.Surface,
        titleContentColor = LabColors.TextPrimary,
        textContentColor = LabColors.TextPrimary,
    )
}