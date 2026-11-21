package com.mobilelab.vibrodiag

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.device.collectDeviceSnapshot
import com.mobilelab.core.files.Storage
import com.mobilelab.core.log.LabLog
import com.mobilelab.vibrodiag.audio.AudioCapability
import com.mobilelab.vibrodiag.audio.AudioProbe
import com.mobilelab.vibrodiag.audio.RecordingEngine
import com.mobilelab.vibrodiag.audio.RecordingRequest
import com.mobilelab.vibrodiag.data.BlindSession
import com.mobilelab.vibrodiag.data.BlindTrialResult
import com.mobilelab.vibrodiag.data.EvaluationReport
import com.mobilelab.vibrodiag.data.Reports
import com.mobilelab.vibrodiag.data.SessionRepository
import com.mobilelab.vibrodiag.data.SessionRepositoryFactory
import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.ml.KnnClassifier
import com.mobilelab.vibrodiag.ml.LabeledFeatures
import com.mobilelab.vibrodiag.ml.LoocvResult
import com.mobilelab.vibrodiag.ml.ModelEvaluation
import com.mobilelab.vibrodiag.ml.ModelSpec
import com.mobilelab.vibrodiag.ml.NoiseEvaluationResult
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.ml.Prediction
import com.mobilelab.vibrodiag.model.AccelMode
import com.mobilelab.vibrodiag.model.AppSettings
import com.mobilelab.vibrodiag.model.Condition
import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.sensor.AccelProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Record, BlindTest, Evaluation, Dataset, Settings }

/** Recording lifecycle, owned by the Activity-wide state holder. */
sealed interface RecordingSaveState {
    data object Idle : RecordingSaveState
    data object Saving : RecordingSaveState
    data class Saved(val sample: StoredSample?, val interrupted: Boolean, val error: String?) : RecordingSaveState
    data class Failed(val message: String) : RecordingSaveState
}

data class ActiveRecording(
    val conditionId: String,
    val groupIndex: Int,
    val blindTrialIndex: Int?,
    val startedAtMs: Long,
)

/** UI view of the in-flight blind trial (label hidden until reveal). */
data class BlindTrialView(
    val trialIndex: Int,
    val conditionId: String,
    val sample: StoredSample?,
    val prediction: Prediction? = null,
    val revealed: Boolean = false,
)

/**
 * Activity scoped state holder: survives orientation changes (the activity
 * declares configChanges) and owns the session, dataset cache and the async
 * evaluation jobs so navigating between screens cannot cancel a running save.
 */
class AppState(val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var repository: SessionRepository by mutableStateOf(SessionRepositoryFactory.openOrCreate(context))
        private set
    var screen by mutableStateOf(Screen.Home)
    var samples by mutableStateOf<List<StoredSample>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings.load(context))
        private set
    var audioCapability by mutableStateOf(AudioProbe.probe(context))
        private set
    var accelCapability by mutableStateOf(AccelProbe.probe(context, settings.accelMode))
        private set
    val deviceSnapshot: DeviceSnapshot = collectDeviceSnapshot(context)

    var activeRecording by mutableStateOf<ActiveRecording?>(null)
        private set
    var saveState by mutableStateOf<RecordingSaveState>(RecordingSaveState.Idle)
        private set

    var blindSession by mutableStateOf<BlindSession?>(null)
        private set
    var blindTrial by mutableStateOf<BlindTrialView?>(null)
        private set
    var blindResults by mutableStateOf<List<BlindTrialResult>>(emptyList())
        private set

    var loocv by mutableStateOf<LoocvResult?>(null)
        private set
    var noiseResult by mutableStateOf<NoiseEvaluationResult?>(null)
        private set
    var noiseRunning by mutableStateOf(false)
        private set
    var noiseProgress by mutableStateOf(0f)
        private set
    var evaluationFile by mutableStateOf<File?>(null)
        private set

    var playingFile by mutableStateOf<File?>(null)
        private set
    var exportFile by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val recorder = RecordingEngine(context)

    private var mediaPlayer: MediaPlayer? = null
    private var noiseCancelled = false

    init {
        LabLog.attach(repository.sessionLogFile)
        LabLog.i("AppState", "session " + repository.sessionId + " at " + repository.root.absolutePath)
        refreshSamples()
        writeManifest()
        restoreBlindResults()
    }

    // ---------------------------------------------------------------- session

    fun refreshSamples() {
        samples = repository.listSamples()
        if (samples.isNotEmpty()) {
            LabLog.i("AppState", "dataset loaded: " + samples.size + " samples")
        }
    }

    fun countsByCondition(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (condition in Conditions.all) counts[condition.id] = 0
        for (sample in samples) {
            if (sample.interrupted) continue
            counts[sample.conditionId] = (counts[sample.conditionId] ?: 0) + 1
        }
        return counts
    }

    fun usableSamples(): List<StoredSample> = samples.filter { !it.interrupted }

    fun nextGroup(conditionId: String): Int? = repository.nextGroup(conditionId)

    fun initialCondition(): String = repository.leastCollectedCondition()

    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings.normalized()
        AppSettings.save(context, settings)
        accelCapability = AccelProbe.probe(context, settings.accelMode)
        writeManifest()
        LabLog.i("AppState", "settings saved duration=" + settings.durationS + "s accel=" + settings.accelMode.id)
    }

    fun refreshCapabilities() {
        audioCapability = AudioProbe.probe(context)
        accelCapability = AccelProbe.probe(context, settings.accelMode)
    }

    private fun writeManifest() {
        runCatching {
            repository.writeManifest(deviceSnapshot, audioCapability, accelCapability, BuildConfig.VERSION_NAME)
        }.onFailure { LabLog.e("AppState", "manifest write failed", it) }
    }

    fun startNewSession() {
        stopPlayback()
        recorder.release()
        repository = SessionRepositoryFactory.createNew(context)
        LabLog.attach(repository.sessionLogFile)
        resetSessionState()
        refreshSamples()
        blindSession = null
        blindTrial = null
        blindResults = emptyList()
        writeManifest()
        showMessage("已创建新会话 " + repository.sessionId)
    }

    fun showMessage(text: String) {
        message = text
    }

    fun clearMessage() {
        message = null
    }

    // -------------------------------------------------------------- recording

    fun startRecording(conditionId: String, groupIndex: Int, blindTrialIndex: Int? = null): String? {
        if (activeRecording != null) return "已有录音正在进行"
        val paths = if (blindTrialIndex == null) {
            repository.samplePaths(conditionId, groupIndex)
        } else {
            repository.blindSamplePaths(blindTrialIndex, conditionId)
        }
        paths.dir.mkdirs()
        val error = recorder.start(RecordingRequest(paths.audio, settings.durationS, settings.accelMode))
        if (error != null) {
            LabLog.w("AppState", "recording start failed: " + error)
            showMessage(error)
            return error
        }
        activeRecording = ActiveRecording(conditionId, groupIndex, blindTrialIndex, System.currentTimeMillis())
        saveState = RecordingSaveState.Idle
        return null
    }

    /**
     * Stops the recorder in the app scope so a lifecycle interruption (incoming
     * call, Home press) still flushes the WAV header, writes the CSV/features and
     * updates the dataset while the screen may already be in the background.
     */
    fun finishRecording(interrupted: Boolean) {
        val active = activeRecording ?: return
        activeRecording = null
        saveState = RecordingSaveState.Saving
        appScope.launch {
            val output = withContext(Dispatchers.IO) { runCatching { recorder.finish() }.getOrNull() }
            if (output == null) {
                saveState = RecordingSaveState.Failed("录音收尾失败，请重试")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (active.blindTrialIndex != null) {
                        repository.persistBlindRecording(active.blindTrialIndex, active.conditionId, output, settings)
                    } else {
                        repository.persistRecording(
                            active.conditionId,
                            active.groupIndex,
                            output,
                            settings,
                            interrupted || output.interrupted,
                        )
                    }
                }
            }
            saved.onSuccess { sample ->
                refreshSamples()
                if (active.blindTrialIndex != null) {
                    blindTrial = BlindTrialView(active.blindTrialIndex, active.conditionId, sample)
                }
                saveState = RecordingSaveState.Saved(sample, interrupted || output.interrupted, output.error)
                LabLog.i("AppState", "recording persisted " + sample.paths.stem)
            }.onFailure { throwable ->
                LabLog.e("AppState", "persist recording failed", throwable)
                saveState = RecordingSaveState.Failed("保存失败：" + (throwable.message ?: throwable.javaClass.simpleName))
            }
        }
    }

    fun resetRecordingState() {
        saveState = RecordingSaveState.Idle
    }

    // ------------------------------------------------------------- blind test

    fun startBlindTest(): String? {
        if (usableSamples().size < 3) return "训练集为空，请先在采集页完成数据采集"
        repository.clearBlindTestData()
        blindSession = BlindSession.create()
        blindResults = emptyList()
        blindTrial = null
        nextBlindTrial()
        LabLog.i("AppState", "blind test started with " + (blindSession?.total ?: 0) + " trials")
        return null
    }

    fun resetBlindTest() {
        repository.clearBlindTestData()
        blindSession = null
        blindTrial = null
        blindResults = emptyList()
        saveState = RecordingSaveState.Idle
    }

    fun nextBlindTrial() {
        val session = blindSession ?: return
        val index = blindResults.size
        if (index >= session.total) {
            blindTrial = null
            return
        }
        val conditionId = session.conditionForTrial(index) ?: return
        blindTrial = BlindTrialView(index, conditionId, null)
        saveState = RecordingSaveState.Idle
    }

    fun buildClassifier(): KnnClassifier? {
        val usable = usableSamples()
        if (usable.isEmpty()) return null
        val classifier = KnnClassifier(ModelSpec.K)
        classifier.fit(usable.map { LabeledFeatures(it.conditionId, it.featureArray) })
        return classifier
    }

    fun predictBlindTrial() {
        val trial = blindTrial ?: return
        val sample = trial.sample ?: return
        val classifier = buildClassifier()
        if (classifier == null) {
            showMessage("训练集为空，无法预测")
            return
        }
        val prediction = classifier.predict(sample.featureArray)
        if (prediction == null) {
            showMessage("预测失败：特征为空")
            return
        }
        blindTrial = trial.copy(prediction = prediction)
    }

    fun revealBlindTrial() {
        val trial = blindTrial ?: return
        val prediction = trial.prediction ?: return
        val sample = trial.sample ?: return
        val condition = Conditions.byId(trial.conditionId) ?: return
        val predictedCondition = Conditions.byId(prediction.label)
        val result = BlindTrialResult(
            trialIndex = trial.trialIndex,
            trueConditionId = condition.id,
            trueConditionLabel = condition.label,
            predictedConditionId = prediction.label,
            predictedConditionLabel = predictedCondition?.label ?: prediction.label,
            confidence = prediction.confidence,
            featuresRelativePath = repository.relativePath(sample.paths.features),
            recordedAtMs = sample.recordedAtMs,
        )
        blindResults = blindResults + result
        runCatching {
            Reports.writeBlindTest(repository.blindCsvFile, repository.sessionId, blindResults, ModelSpec.DESCRIPTOR)
        }.onFailure { LabLog.e("AppState", "blind csv write failed", it) }
        blindTrial = trial.copy(revealed = true)
        LabLog.i(
            "AppState",
            "blind trial " + trial.trialIndex + " true=" + condition.id + " predicted=" + prediction.label +
                " correct=" + result.correct + " confidence=" + prediction.confidence,
        )
    }

    fun blindAccuracy(): Double =
        if (blindResults.isEmpty()) 0.0 else blindResults.count { it.correct }.toDouble() / blindResults.size

    fun blindConfusion(): List<List<Int>> = ModelEvaluation.confusion(
        Conditions.ids,
        blindResults.map { it.trueConditionId },
        blindResults.map { it.predictedConditionId },
    )

    fun blindIsComplete(): Boolean {
        val session = blindSession ?: return false
        return blindResults.size >= session.total
    }

    private fun restoreBlindResults() {
        val rows = Reports.readBlindRows(repository.blindCsvFile)
        if (rows.isEmpty()) return
        blindResults = rows.mapNotNull { row ->
            val condition = Conditions.byId(row.trueConditionId) ?: return@mapNotNull null
            BlindTrialResult(
                trialIndex = row.trialIndex,
                trueConditionId = condition.id,
                trueConditionLabel = condition.label,
                predictedConditionId = row.predictedConditionId,
                predictedConditionLabel = Conditions.labelOf(row.predictedConditionId),
                confidence = row.confidence,
                featuresRelativePath = "blind",
                recordedAtMs = 0L,
            )
        }
        LabLog.i("AppState", "restored " + blindResults.size + " blind trials from CSV")
    }

    // ------------------------------------------------------------- evaluation

    fun runLoocv(): LoocvResult? {
        val items = usableSamples().map { LabeledFeatures(it.conditionId, it.featureArray) }
        if (items.size < 2) {
            showMessage("样本不足，至少需要 2 组数据")
            return null
        }
        val result = ModelEvaluation.loocv(items, Conditions.ids)
        loocv = result
        LabLog.i("AppState", "LOOCV accuracy=" + result.accuracy + " (" + result.correct + "/" + result.total + ")")
        return result
    }

    fun runNoiseEvaluation() {
        if (noiseRunning) return
        val usable = usableSamples()
        if (usable.isEmpty()) {
            showMessage("请先采集数据")
            return
        }
        val classifier = buildClassifier() ?: return
        noiseCancelled = false
        noiseRunning = true
        noiseProgress = 0f
        appScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    NoiseEvaluator.evaluate(
                        samples = usable,
                        classifier = classifier,
                        conditionIds = Conditions.ids,
                        onProgress = { progress -> noiseProgress = progress },
                        isCancelled = { noiseCancelled },
                    )
                }
                noiseResult = result
                withContext(Dispatchers.IO) {
                    Reports.writeNoiseEval(repository.noiseCsvFile, repository.sessionId, result.rows, ModelSpec.DESCRIPTOR)
                }
                LabLog.i("AppState", "noise evaluation finished rows=" + result.rows.size)
            } catch (throwable: Throwable) {
                LabLog.e("AppState", "noise evaluation failed", throwable)
                showMessage("抗噪评估失败：" + (throwable.message ?: throwable.javaClass.simpleName))
            } finally {
                noiseRunning = false
            }
        }
    }

    fun cancelNoiseEvaluation() {
        noiseCancelled = true
        showMessage("正在停止抗噪评估…")
    }

    fun generateEvaluationReport(notes: String): File? {
        val usable = usableSamples()
        if (usable.isEmpty()) {
            showMessage("数据集为空，无法生成评估报告")
            return null
        }
        val loocvResult = loocv ?: runLoocv()
        val blindRows = Reports.readBlindRows(repository.blindCsvFile)
        val noise = noiseResult ?: run {
            val rows = Reports.readNoiseRows(repository.noiseCsvFile)
            if (rows.isEmpty()) null else NoiseEvaluationResult(
                rows,
                NoiseEvaluator.DEFAULT_SNR_LEVELS,
                NoiseEvaluator.DEFAULT_NOISE_TYPES,
            )
        }
        val conditionSamples = LinkedHashMap<String, Int>()
        for (condition in Conditions.all) {
            conditionSamples[condition.id] = usable.count { it.conditionId == condition.id }
        }
        val json = EvaluationReport.build(
            sessionId = repository.sessionId,
            generatedAtMs = System.currentTimeMillis(),
            trainingSamples = usable.size,
            loocv = loocvResult,
            blindRows = blindRows,
            noise = noise,
            conditionSamples = conditionSamples,
            notes = notes,
        )
        val file = repository.evaluationFile
        return runCatching {
            EvaluationReport.write(file, json)
            evaluationFile = file
            LabLog.i("AppState", "evaluation report written " + file.absolutePath)
            file
        }.onFailure {
            LabLog.e("AppState", "evaluation report write failed", it)
            showMessage("评估报告写入失败")
        }.getOrNull()
    }

    fun evaluationReportText(): String =
        runCatching { repository.evaluationFile.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    // ---------------------------------------------------------------- dataset

    fun deleteConditionSamples(conditionId: String) {
        stopPlayback()
        val removed = repository.deleteConditionSamples(conditionId)
        resetDerivedResults()
        refreshSamples()
        showMessage("已删除 " + Conditions.labelOf(conditionId) + " 的 " + removed + " 组")
    }

    fun deleteAllSamples() {
        stopPlayback()
        val removed = repository.deleteAllSamples()
        resetDerivedResults()
        refreshSamples()
        showMessage("已删除全部 " + removed + " 组数据与派生评估")
    }

    private fun resetDerivedResults() {
        loocv = null
        noiseResult = null
        noiseProgress = 0f
        noiseRunning = false
        evaluationFile = null
    }

    fun deleteSample(sample: StoredSample) {
        stopPlayback()
        repository.deleteSample(sample)
        refreshSamples()
        showMessage("已删除 " + sample.paths.stem)
    }

    fun togglePlayback(file: File) {
        if (playingFile == file) {
            stopPlayback()
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { stopPlayback() }
            mediaPlayer = player
            playingFile = file
        }.onFailure { throwable ->
            LabLog.e("AppState", "playback failed " + file.absolutePath, throwable)
            runCatching { player.release() }
            showMessage("无法播放该录音")
        }
    }

    fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        playingFile = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
    }

    // ----------------------------------------------------------------- export

    fun createSessionZip(): File {
        val zip = File(context.cacheDir, "vibrodiag_" + repository.sessionId + ".zip")
        if (zip.exists()) zip.delete()
        Storage.zipDirectory(repository.root, zip)
        exportFile = zip
        return zip
    }

    fun createSessionZipAsync(onDone: (File?) -> Unit = {}) {
        appScope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching { createSessionZip() }.onFailure {
                    LabLog.e("AppState", "zip export failed", it)
                }.getOrNull()
            }
            onDone(zip)
        }
    }

    fun clearExportFile() {
        exportFile = null
    }

    // ------------------------------------------------------------- lifecycle

    fun dispose() {
        stopPlayback()
        recorder.release()
    }

    private fun resetSessionState() {
        loocv = null
        noiseResult = null
        noiseProgress = 0f
        evaluationFile = null
        exportFile = null
        playingFile = null
        saveState = RecordingSaveState.Idle
        activeRecording = null
        screen = Screen.Home
    }
}
