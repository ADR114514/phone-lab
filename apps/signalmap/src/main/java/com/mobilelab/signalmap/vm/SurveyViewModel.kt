package com.mobilelab.signalmap.vm

import android.annotation.SuppressLint

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.mobilelab.core.files.Storage
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.signalmap.data.AppSettings
import com.mobilelab.signalmap.data.MeasurementTarget
import com.mobilelab.signalmap.data.PresetPoints
import com.mobilelab.signalmap.data.RepeatGate
import com.mobilelab.signalmap.data.SessionIds
import com.mobilelab.signalmap.data.StateStore
import com.mobilelab.signalmap.data.SurveyPoint
import com.mobilelab.signalmap.data.SurveyProgress
import com.mobilelab.signalmap.data.SurveyRepository
import com.mobilelab.signalmap.data.SurveyRow
import com.mobilelab.signalmap.service.EnvironmentProbe
import com.mobilelab.signalmap.service.EnvironmentStatus
import com.mobilelab.signalmap.service.LiveSample
import com.mobilelab.signalmap.service.MeasurementBlockedException
import com.mobilelab.signalmap.service.MeasurementCallback
import com.mobilelab.signalmap.service.MeasurementPipeline
import com.mobilelab.signalmap.service.MeasurementRequest
import com.mobilelab.signalmap.service.MeasurementStep
import com.mobilelab.signalmap.service.WifiReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Top level navigation targets; a small hand rolled stack keeps the app single activity. */
sealed interface Screen {
    data object Home : Screen
    data object Points : Screen
    data class Measure(val pointId: String) : Screen
    data object Result : Screen
    data object Settings : Screen
}

/** State of one repeat measurement shown on the measure screen. */
sealed interface MeasurementUi {
    data object Idle : MeasurementUi
    data class Running(
        val pointId: String,
        val repeatIndex: Int,
        val step: MeasurementStep,
        val live: LiveSample,
        val rttSeries: List<Float>,
    ) : MeasurementUi

    data class Done(val pointId: String, val repeatIndex: Int, val note: String) : MeasurementUi
    data class Failed(val pointId: String, val reason: String) : MeasurementUi
}

/**
 * Single owner of the survey state. Everything is persisted through
 * [StateStore] plus the session directory, so rotation (handled by
 * configChanges) and process death (restore in init) both resume correctly.
 */
class SurveyViewModel(application: Application) : AndroidViewModel(application) {

    // AndroidViewModel 持有的就是 applicationContext，不存在 Activity 泄漏。
    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext
    private val store = StateStore(context)
    private val repository = SurveyRepository(context)
    private val wifiReader = WifiReader(context)
    private val pipeline = MeasurementPipeline(context, wifiReader)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var points by mutableStateOf(store.loadPoints())
        private set
    var progress by mutableStateOf(store.loadProgress())
        private set
    var settings by mutableStateOf(store.settings)
        private set
    var environment by mutableStateOf(EnvironmentProbe.check(context))
        private set
    var rows by mutableStateOf<List<SurveyRow>>(emptyList())
        private set
    var sessionId by mutableStateOf<String?>(null)
        private set
    var sessionFiles by mutableStateOf<SurveyRepository.SessionFiles?>(null)
        private set
    var measurement by mutableStateOf<MeasurementUi>(MeasurementUi.Idle)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    private val backStack = mutableListOf<Screen>(Screen.Home)
    private var sessionStartedAtMs = 0L

    init {
        restoreSession()
        LabLog.i(TAG, "view model initialised: session=${sessionId ?: "none"} rows=${rows.size}")
    }

    // ------------------------------------------------------------------ state
    fun postMessage(value: String?) {
        message = value
    }

    fun refreshEnvironment() {
        environment = EnvironmentProbe.check(context)
    }

    fun navigate(target: Screen) {
        if (screen == target) return
        backStack.add(target)
        screen = target
    }

    fun canGoBack(): Boolean = backStack.size > 1

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        screen = backStack.last()
        return true
    }

    /** Resets the measurement panel when a different station is opened. */
    fun enterMeasure(pointId: String) {
        ensureSession()
        measurement = MeasurementUi.Idle
        navigate(Screen.Measure(pointId))
    }

    /** Switches station without growing the back stack (used by the station chips). */
    fun switchMeasurePoint(pointId: String) {
        ensureSession()
        measurement = MeasurementUi.Idle
        val target = Screen.Measure(pointId)
        if (backStack.lastOrNull() is Screen.Measure) {
            backStack[backStack.lastIndex] = target
        } else {
            backStack.add(target)
        }
        screen = target
    }

    // ------------------------------------------------------------------ session
    @Synchronized
    fun ensureSession(): SurveyRepository.SessionFiles? {
        sessionFiles?.let { return it }
        val now = System.currentTimeMillis()
        val id = SessionIds.next(now, repository.listExistingSessionIds())
        val created = runCatching { repository.createSession(id, points, settings) }
            .onFailure { LabLog.e(TAG, "cannot create session", it) }
            .getOrNull() ?: run {
            message = "创建会话目录失败，请检查存储空间"
            return null
        }
        sessionId = id
        sessionStartedAtMs = now
        sessionFiles = created
        store.sessionId = id
        store.sessionDirName = created.dir.name
        store.sessionStartedAtMs = now
        rows = repository.loadRows(created)
        return created
    }

    private fun restoreSession() {
        val id = store.sessionId ?: return
        val dirName = store.sessionDirName ?: return
        val files = runCatching { repository.openSession(id, dirName) }
            .onFailure { LabLog.e(TAG, "cannot reopen session dir $dirName", it) }
            .getOrNull()
        if (files == null) {
            LabLog.w(TAG, "stored session dir missing, starting fresh: $dirName")
            store.clearSession()
            return
        }
        sessionId = id
        sessionFiles = files
        sessionStartedAtMs = store.sessionStartedAtMs
        val loaded = repository.loadRows(files)
        rows = loaded
        progress = progress.reconciledWith(loaded, System.currentTimeMillis())
        store.saveProgress(progress)
        LabLog.i(TAG, "restored session $id with ${loaded.size} rows")
    }

    // ----------------------------------------------------------------- progress
    fun nextTarget(): MeasurementTarget? = progress.nextTarget(points)

    fun pointById(pointId: String): SurveyPoint? = points.firstOrNull { it.id == pointId }

    fun repeatsFor(pointId: String): Int = progress.repeats(pointId)

    fun nextRepeatIndex(pointId: String): Int? = progress.nextRepeatIndex(pointId)

    fun isAllDone(): Boolean = progress.allDone(points)

    fun repeatGateRemainingMs(pointId: String): Long = RepeatGate.remainingMs(
        lastCompletedAtMs = progress.lastCompletedAt(pointId),
        nowMs = System.currentTimeMillis(),
        intervalSec = AppSettings.REPEAT_INTERVAL_SEC,
    )

    // -------------------------------------------------------------- measurement
    fun startMeasurement(pointId: String? = null) {
        if (measurement is MeasurementUi.Running) {
            message = "上一轮测量尚未结束"
            return
        }
        refreshEnvironment()
        if (!environment.measurementReady) {
            val reason = environment.blockingSummary
            measurement = MeasurementUi.Failed(pointId.orEmpty(), reason)
            message = "环境不满足，已阻止测量：$reason"
            LabLog.w(TAG, "measurement refused: $reason")
            return
        }
        val target: MeasurementTarget = if (pointId != null) {
            val point = points.firstOrNull { it.id == pointId } ?: run {
                message = "点位不存在：$pointId"
                return
            }
            val repeat = progress.nextRepeatIndex(point.id) ?: run {
                message = "${point.id} 的 3 次测量已完成，请选择下一个点位"
                return
            }
            MeasurementTarget(point, repeat)
        } else {
            progress.nextTarget(points) ?: run {
                message = "8 个点位 × 3 次测量已全部完成，请到结果页导出"
                return
            }
        }
        val remaining = repeatGateRemainingMs(target.point.id)
        if (remaining > 0) {
            message = "同一位置两次测量需间隔 30 秒，请再等待 ${remaining / 1000 + 1} 秒"
            return
        }
        val files = ensureSession() ?: return
        val startedAt = System.currentTimeMillis()
        val request = MeasurementRequest(
            point = target.point,
            repeatIndex = target.repeatIndex,
            sessionId = files.sessionId,
            settings = settings,
            timestampIso = TimeFmt.isoLocal(startedAt),
            photoFile = target.point.photoFile,
        )
        measurement = MeasurementUi.Running(
            pointId = target.point.id,
            repeatIndex = target.repeatIndex,
            step = MeasurementStep.CONNECTION,
            live = LiveSample(rssiDbm = environment.rssiDbm),
            rttSeries = emptyList(),
        )
        LabLog.i(TAG, "measurement start ${target.point.id} r${target.repeatIndex} session=${files.sessionId}")
        scope.launch {
            try {
                val callback = object : MeasurementCallback {
                    // The pipeline runs on Dispatchers.IO; live updates are posted
                    // back to the main dispatcher before touching Compose state.
                    override fun onStep(step: MeasurementStep) {
                        scope.launch {
                            val current = measurement
                            if (current is MeasurementUi.Running) {
                                measurement = current.copy(step = step)
                            }
                        }
                    }

                    override fun onLive(sample: LiveSample) {
                        scope.launch {
                            val current = measurement
                            if (current is MeasurementUi.Running) {
                                val series = if (sample.sampleLatencyMs != null) {
                                    (current.rttSeries + sample.sampleLatencyMs.toFloat()).takeLast(40)
                                } else {
                                    current.rttSeries
                                }
                                measurement = current.copy(live = sample, rttSeries = series)
                            }
                        }
                    }

                    override fun onLog(logMessage: String) {
                        LabLog.i(TAG, logMessage)
                    }
                }
                val outcome = pipeline.measure(request, callback)

                val current = measurement
                if (current is MeasurementUi.Running) {
                    measurement = current.copy(step = MeasurementStep.WRITE)
                }
                val completedAt = System.currentTimeMillis()
                val newProgress = progress.register(target.point.id, target.repeatIndex, completedAt)
                val newRows = rows + outcome.row
                withContext(Dispatchers.IO) {
                    repository.writeScan(files, target.point.id, target.repeatIndex, outcome.scanEntries)
                    repository.appendRow(files, outcome.row)
                }
                rows = newRows
                progress = newProgress
                store.saveProgress(newProgress)
                withContext(Dispatchers.IO) {
                    runCatching { repository.writePoints(files, points) }
                    runCatching {
                        repository.writeManifest(
                            files, files.sessionId, sessionStartedAtMs, points, newRows, newProgress, settings, false,
                        )
                    }.onFailure { LabLog.w(TAG, "manifest refresh failed: ${it.message}") }
                }
                measurement = MeasurementUi.Done(target.point.id, target.repeatIndex, outcome.row.note)
                message = "${target.point.id} 第 ${target.repeatIndex}/3 次测量完成，已写入 ${files.surveyCsv.name}"
            } catch (blocked: MeasurementBlockedException) {
                LabLog.w(TAG, "measurement blocked mid-flight: ${blocked.message}")
                measurement = MeasurementUi.Failed(target.point.id, blocked.message ?: "环境不满足")
                message = blocked.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LabLog.e(TAG, "measurement failed for ${target.point.id}", error)
                measurement = MeasurementUi.Failed(
                    target.point.id,
                    "测量失败：${error.javaClass.simpleName}: ${error.message}",
                )
                message = "测量失败，未写入任何数据"
            }
        }
    }

    fun clearMeasurementState() {
        measurement = MeasurementUi.Idle
    }

    // ------------------------------------------------------------------- photos
    fun photoFile(relativePath: String): File? {
        if (relativePath.isEmpty()) return null
        val files = sessionFiles ?: return null
        return File(files.dir, relativePath)
    }

    fun newPhotoTarget(pointId: String): SurveyRepository.PhotoTarget? {
        val files = ensureSession() ?: return null
        return repository.newPhotoFile(files, pointId, System.currentTimeMillis())
    }

    fun onPhotoCaptured(pointId: String, target: SurveyRepository.PhotoTarget) {
        val files = sessionFiles ?: return
        val point = points.firstOrNull { it.id == pointId } ?: return
        val previous = point.photoFile
        points = points.map { if (it.id == pointId) it.copy(photoFile = target.relativePath) else it }
        store.savePoints(points)
        rows = rows.map { if (it.pointId == pointId) it.copy(photoFile = target.relativePath) else it }
        if (previous.isNotEmpty() && previous != target.relativePath) {
            runCatching { File(files.dir, previous).delete() }
        }
        persistPhotoReference(files, pointId, target.relativePath)
        message = "已保存照片 ${target.relativePath}"
        LabLog.i(TAG, "photo stored for $pointId: ${target.relativePath}")
    }

    fun clearPhoto(pointId: String) {
        val files = sessionFiles ?: return
        val point = points.firstOrNull { it.id == pointId } ?: return
        val previous = point.photoFile
        points = points.map { if (it.id == pointId) it.copy(photoFile = "") else it }
        store.savePoints(points)
        rows = rows.map { if (it.pointId == pointId) it.copy(photoFile = "") else it }
        if (previous.isNotEmpty()) {
            runCatching { File(files.dir, previous).delete() }
        }
        persistPhotoReference(files, pointId, "")
        message = "已跳过拍照，photo_file 将留空并在 note 中记录"
    }

    // ------------------------------------------------------------------- points
    fun updatePoint(updated: SurveyPoint) {
        points = points.map { if (it.id == updated.id) updated else it }
        store.savePoints(points)
        persistStructures()
        message = "点位 ${updated.id} 已更新"
    }

    fun resetPointsToPreset() {
        points = PresetPoints.defaults()
        store.savePoints(points)
        persistStructures()
        message = "已恢复 8 个内置点位预设"
    }

    // ----------------------------------------------------------------- settings
    fun saveSettings(updated: AppSettings) {
        settings = updated.sanitized()
        store.settings = settings
        message = "设置已保存"
        LabLog.i(TAG, "settings saved: tcp=${settings.tcpTarget} rtt=${settings.rttAttempts} http=${settings.httpTestUrl}")
    }

    fun resetProgress() {
        if (measurement is MeasurementUi.Running) {
            message = "测量进行中，无法重置"
            return
        }
        store.clearProgress()
        store.clearSession()
        sessionFiles = null
        sessionId = null
        rows = emptyList()
        progress = SurveyProgress.EMPTY
        measurement = MeasurementUi.Idle
        message = "进度已重置；旧数据仍保留在实验目录中，下次测量将创建新会话"
        LabLog.i(TAG, "progress reset by operator")
    }

    // ------------------------------------------------------------------- export
    fun buildZip(onReady: (File?) -> Unit) {
        if (busy) return
        val files = sessionFiles
        if (files == null) {
            message = "尚未创建会话，没有可导出的数据"
            onReady(null)
            return
        }
        if (rows.isEmpty()) {
            message = "还没有测量数据，无法导出"
            onReady(null)
            return
        }
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.buildExportZip(
                        files = files,
                        rows = rows,
                        points = points,
                        progress = progress,
                        settings = settings,
                        startedAtMs = sessionStartedAtMs,
                        hashBssid = settings.hashBssidOnExport,
                    )
                }
            }
            busy = false
            result.onSuccess { zip ->
                message = "ZIP 已生成：${zip.name}（${Storage.humanBytes(zip.length())}）"
                onReady(zip)
            }.onFailure { error ->
                LabLog.e(TAG, "zip export failed", error)
                message = "导出失败：${error.message}"
                onReady(null)
            }
        }
    }

    fun buildCsvExport(onReady: (File?) -> Unit) {
        if (busy) return
        val files = sessionFiles
        if (files == null || rows.isEmpty()) {
            message = "还没有可导出的测量数据"
            onReady(null)
            return
        }
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.buildSurveyCsvExport(files, rows, settings.hashBssidOnExport)
                }
            }
            busy = false
            result.onSuccess { csv ->
                message = "CSV 已生成：${csv.name}"
                onReady(csv)
            }.onFailure { error ->
                LabLog.e(TAG, "csv export failed", error)
                message = "导出失败：${error.message}"
                onReady(null)
            }
        }
    }

    // -------------------------------------------------------------------- misc
    fun scanFileCount(): Int = sessionFiles?.let { repository.scanFiles(it).size } ?: 0

    fun photoFileCount(): Int = sessionFiles?.let { repository.photoCount(it) } ?: 0

    fun sessionDirName(): String = sessionFiles?.dir?.name ?: "尚未创建"

    fun sessionSummary(): String = "${environment.ssid.ifEmpty { "未连接 Wi-Fi" }} · ${sessionId ?: "未开始"}"

    /**
     * Updates the photo_file column of the rows already on disk (targeted
     * rewrite under the repository write lock) and then refreshes the
     * catalogue/manifest. Never rewrites the survey table from a stale
     * in-memory snapshot.
     */
    private fun persistPhotoReference(
        files: SurveyRepository.SessionFiles,
        pointId: String,
        relativePath: String,
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.updateRowPhoto(files, pointId, relativePath) }
                    .onFailure { LabLog.e(TAG, "photo_file update failed", it) }
                persistPointsAndManifest(files)
            }
        }
    }

    /** Refreshes points.csv + manifest.json (survey rows are never rewritten). */
    private fun persistStructures() {
        val files = sessionFiles ?: return
        scope.launch {
            withContext(Dispatchers.IO) { persistPointsAndManifest(files) }
        }
    }

    private fun persistPointsAndManifest(files: SurveyRepository.SessionFiles) {
        val snapshotPoints = points
        val snapshotRows = rows
        val snapshotProgress = progress
        runCatching { repository.writePoints(files, snapshotPoints) }
            .onFailure { LabLog.w(TAG, "points.csv write failed: ${it.message}") }
        runCatching {
            repository.writeManifest(
                files, files.sessionId, sessionStartedAtMs, snapshotPoints, snapshotRows,
                snapshotProgress, settings, false,
            )
        }.onFailure { LabLog.w(TAG, "manifest refresh failed: ${it.message}") }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SurveyViewModel"
    }
}
