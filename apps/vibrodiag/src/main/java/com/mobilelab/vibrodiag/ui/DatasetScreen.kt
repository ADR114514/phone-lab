package com.mobilelab.vibrodiag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.EmptyState
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.model.Conditions

@Composable
fun DatasetScreen(state: AppState) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var detailText by remember { mutableStateOf<String?>(null) }
    var detailTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<StoredSample?>(null) }

    DisposableEffect(Unit) {
        onDispose { state.stopPlayback() }
    }

    val options = listOf("全部") + Conditions.all.map { it.shortLabel }
    val filtered = if (filter == 0) state.samples else state.samples.filter { it.conditionId == Conditions.all[filter - 1].id }

    LabScaffold(
        title = "数据集",
        subtitle = "按工况浏览 · 播放 · 查看 meta · 删除",
        status = "共 " + state.samples.size + " 组",
        onBack = { state.screen = Screen.Home },
    ) {
        ChipRow(options = options, selectedIndex = filter, onSelect = { filter = it })
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    EmptyState("暂无录音", "到采集页完成第一组声振采集后这里会显示文件")
                }
            }
            items(filtered, key = { it.paths.dir.absolutePath }) { sample ->
                DatasetRow(
                    state = state,
                    sample = sample,
                    onDetail = {
                        detailTitle = "meta.json · " + sample.paths.stem
                        detailText = runCatching { sample.paths.meta.readText() }.getOrElse { "无法读取 meta.json" }
                    },
                    onDelete = { deleteTarget = sample },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (detailText != null) {
        TextContentDialog(detailTitle, detailText ?: "") { detailText = null }
    }

    val target = deleteTarget
    if (target != null) {
        ConfirmDialog(
            title = "删除录音",
            text = "将删除 " + target.paths.stem + " 的 WAV/CSV/meta 并更新 dataset_index，操作不可撤销。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                state.deleteSample(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun DatasetRow(
    state: AppState,
    sample: StoredSample,
    onDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    val playing = state.playingFile == sample.paths.audio
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sample.paths.stem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (sample.interrupted) StatusPill("不完整", PillTone.Warning)
                }
                Spacer(Modifier.height(4.dp))
                HintText(
                    "时长 " + Fmt.value(sample.durationS, 1) + " s · RMS " + Fmt.dbfs(sample.audioRmsDbfs) +
                        " · 峰值 " + Fmt.hz(sample.audioPeakHz),
                )
                HintText("振动 RMS " + Fmt.value(sample.accelRms, 3) + " m/s² · 目录 " + sample.paths.dir.name)
            }
            StatusPill(Conditions.shortLabelOf(sample.conditionId), PillTone.Info)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkButton(if (playing) "停止" else "播放", { state.togglePlayback(sample.paths.audio) })
            LinkButton("详情", { onDetail() })
            LinkButton("删除", { onDelete() })
            Spacer(Modifier.weight(1f))
            Text(
                text = sample.paths.features.name,
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}