package com.mobilelab.signalmap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilelab.core.csv.CsvBuilder
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.ui.EmptyState
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.data.CsvCodec
import com.mobilelab.signalmap.data.DataContract
import com.mobilelab.signalmap.vm.SurveyViewModel

@Composable
fun ResultScreen(vm: SurveyViewModel, onBack: () -> Unit) {
    val exporter = rememberFileExporter()
    var viewerFile by remember { mutableStateOf<File?>(null) }
    val rows = vm.rows
    val aggregates = remember(rows) { aggregateByPoint(rows) }
    val notes = remember(rows) { rows.filter { it.note.isNotEmpty() } }
    val tableRows = remember(rows) {
        rows.map { row -> CsvCodec.rowValues(row, hashBssid = false).map { CsvBuilder.render(it) } }
    }
    val zipMime = "application/zip"
    val csvMime = "text/csv"

    LabScaffold(
        title = "测量结果",
        subtitle = "${rows.size}/24 行 · ${vm.sessionId ?: "未开始会话"}",
        onBack = onBack,
        actions = {
            StatusPill(
                text = if (rows.size >= DataContract.TOTAL_ROWS) "数据齐全" else "进行中",
                tone = if (rows.size >= DataContract.TOTAL_ROWS) PillTone.Success else PillTone.Warning,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MessageBanner(vm.message) { vm.postMessage(null) }

            SectionCard(title = "数据概览") {
                KeyValueRow("会话 ID", vm.sessionId ?: "--")
                KeyValueRow("会话目录", vm.sessionDirName())
                KeyValueRow("主表行数", "${rows.size} / 24")
                KeyValueRow("扫描明细", "${vm.scanFileCount()} 个 scan CSV")
                KeyValueRow("点位照片", "${vm.photoFileCount()} 张")
                KeyValueRow("主表文件", "survey_${vm.sessionId ?: "--"}.csv")
                KeyValueRow(
                    "BSSID 脱敏",
                    if (vm.settings.hashBssidOnExport) "导出时哈希（${com.mobilelab.signalmap.data.BssidHash.ALGORITHM_NAME}）" else "导出保留原值",
                )
                HintText(
                    "导出 ZIP 目录符合数据字典 2.4：manifest.json / survey_<session>.csv / points.csv / " +
                        "scan_*_r*.csv / photos/ / session.log。",
                )
            }


            SectionCard(title = "点位照片（" + vm.points.count { it.photoFile.isNotEmpty() } + " 张）") {
                val withPhotos = vm.points.filter { it.photoFile.isNotEmpty() }
                if (withPhotos.isEmpty()) {
                    HintText("还没有照片：到点位后用相机拍照，拍完即可在这里查看；导出 ZIP 会包含 photos/ 原图。")
                } else {
                    withPhotos.forEach { point ->
                        val file = vm.photoFile(point.photoFile)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (file != null) {
                                PhotoThumb(file) { viewerFile = file }
                            } else {
                                Box(
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(LabColors.SurfaceHigh),
                                    contentAlignment = Alignment.Center,
                                ) { Text("缺失", style = MaterialTheme.typography.labelSmall, color = LabColors.Error) }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = point.id + " · " + point.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LabColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                HintText(point.photoFile)
                            }
                        }
                    }
                    HintText("点击缩略图查看大图；照片原图在导出 ZIP 的 photos/ 目录中。")

                    // 兜底：即使点位引用丢失（例如重装/清理偏好），photos/ 里的原图仍可查看。
                    val referenced = withPhotos.map { File(it.photoFile).name }.toSet()
                    val orphanPhotos = vm.sessionFiles?.photosDir?.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) && it.name !in referenced }
                        .orEmpty()
                        .sortedBy { it.name }
                    if (orphanPhotos.isNotEmpty()) {
                        HintText("未关联到点位的照片（" + orphanPhotos.size + " 张）")
                        orphanPhotos.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PhotoThumb(file) { viewerFile = file }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    HintText("photos/" + file.name)
                                }
                            }
                        }
                    }

                }
            }
            SectionCard(title = "导出") {
                if (rows.isEmpty()) {
                    HintText("还没有测量数据；完成至少一次测量后即可导出。")
                } else {
                    PrimaryButton(
                        text = if (vm.busy) "正在生成…" else "生成并保存 ZIP",
                        onClick = {
                            vm.buildZip { file -> if (file != null) exporter.saveAs(file, zipMime) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        loading = vm.busy,
                        enabled = !vm.busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = "分享 ZIP",
                            onClick = {
                                vm.buildZip { file -> if (file != null) exporter.share(file, zipMime) }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !vm.busy,
                        )
                        SecondaryButton(
                            text = "保存 CSV",
                            onClick = {
                                vm.buildCsvExport { file -> if (file != null) exporter.saveAs(file, csvMime) }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !vm.busy,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = "分享 CSV",
                        onClick = {
                            vm.buildCsvExport { file -> if (file != null) exporter.share(file, csvMime) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !vm.busy,
                    )
                }
            }

            if (aggregates.isEmpty()) {
                SectionCard(title = "按点位聚合") {
                    EmptyState("暂无数据", "完成测量后这里会显示每个点位的均值对比")
                }
            } else {
                SectionCard(title = "各点位 RSSI 均值") {
                    // Only stations with a real measurement are charted; a null
                    // mean means "not measured" and must not be drawn as 0.
                    ComparisonBars(
                        items = aggregates.mapNotNull { aggregate ->
                            aggregate.avgRssi?.let { aggregate.pointId to it.toFloat() }
                        },
                        unit = "dBm",
                        color = LabColors.Primary,
                    )
                }
                SectionCard(title = "各点位平均延迟") {
                    ComparisonBars(
                        items = aggregates.mapNotNull { aggregate ->
                            aggregate.avgLatency?.let { aggregate.pointId to it.toFloat() }
                        },
                        unit = "ms",
                        color = LabColors.Secondary,
                    )
                }
                SectionCard(title = "各点位丢包率") {
                    ComparisonBars(
                        items = aggregates.mapNotNull { aggregate ->
                            aggregate.avgLossPct?.let { aggregate.pointId to it.toFloat() }
                        },
                        unit = "%",
                        color = LabColors.Error,
                    )
                }
            }

            SectionCard(title = "24 行原始数据预览（${rows.size} 行）") {
                if (rows.isEmpty()) {
                    EmptyState("暂无数据", "先完成测量再查看表格预览")
                } else {
                    DataTablePreview(header = DataContract.SURVEY_HEADER, rows = tableRows)
                    Spacer(Modifier.height(6.dp))
                    HintText("列名与顺序严格对应 docs/DATA_DICTIONARY.md 2.1；空字段表示该次测量无数据（不会写 0/NaN）。")
                }
            }

            SectionCard(title = "按点位聚合明细") {
                if (aggregates.isEmpty()) {
                    HintText("暂无数据")
                } else {
                    aggregates.forEach { aggregate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = aggregate.pointId,
                                style = MaterialTheme.typography.labelMedium,
                                color = LabColors.Primary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "RSSI ${aggregate.avgRssi.fmt()} dBm · 延迟 ${aggregate.avgLatency.fmt()} ms · " +
                                    "丢包 ${aggregate.avgLossPct.fmt()}% · AP ${aggregate.avgApCount.fmt(0)} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = LabColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (notes.isNotEmpty()) {
                SectionCard(title = "异常 / 环境备注（${notes.size} 条）") {
                    notes.forEach { row ->
                        Text(
                            text = "${row.pointId} r${row.repeatIndex}：${row.note}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LabColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        viewerFile?.let { file -> PhotoViewerDialog(file) { viewerFile = null } }
    }
}
@Composable
private fun PhotoThumb(file: File, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, targetSize = 512) }
    }
    Box(
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(LabColors.SurfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("加载…", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        }
    }
}

@Composable
private fun PhotoViewerDialog(file: File, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, targetSize = 1600) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("图片加载失败或文件已不存在", color = LabColors.Error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}

/** 按目标尺寸做 inSampleSize 采样解码，避免 3:4 原图直接进内存导致 OOM。 */
private fun decodeSampled(file: File, targetSize: Int): Bitmap? = runCatching {
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > targetSize || bounds.outHeight / sample > targetSize) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)
}.getOrNull()
