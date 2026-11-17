package com.mobilelab.vibrodiag

import com.mobilelab.core.signal.WavWriter
import com.mobilelab.vibrodiag.data.AccelCsv
import com.mobilelab.vibrodiag.data.AccelSample
import com.mobilelab.vibrodiag.data.CsvReader
import com.mobilelab.vibrodiag.data.FeaturesCsv
import com.mobilelab.vibrodiag.data.Reports
import com.mobilelab.vibrodiag.data.SamplePaths
import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.features.FeaturePipeline
import com.mobilelab.vibrodiag.features.FeatureVector
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.KnnClassifier
import com.mobilelab.vibrodiag.ml.LabeledFeatures
import com.mobilelab.vibrodiag.ml.ModelEvaluation
import com.mobilelab.vibrodiag.ml.ModelSpec
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.data.BlindSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Headless end-to-end check of the Android-free data path:
 * synthetic WAV + accel CSV -> contract features CSV -> kNN/LOOCV -> noise table
 * -> CSV reports. Uses a distinct dominant tone per condition so a perfect score
 * is the expected result.
 */
class PipelineIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val audioRate = 44100
    private val audioSeconds = 1.0
    private val conditionTones = mapOf(
        "C1_NORMAL_LOW" to 500.0,
        "C2_NORMAL_HIGH" to 900.0,
        "C3_IMBALANCE" to 1300.0,
        "C4_BLOCKED" to 1700.0,
        "C5_HAIRDRYER" to 2100.0,
    )
    private val accelTones = mapOf(
        "C1_NORMAL_LOW" to 10.0,
        "C2_NORMAL_HIGH" to 14.0,
        "C3_IMBALANCE" to 18.0,
        "C4_BLOCKED" to 22.0,
        "C5_HAIRDRYER" to 26.0,
    )

    private lateinit var root: File

    private fun buildDataset(): List<StoredSample> {
        root = folder.newFolder("20261118_120000_VD-20261118-001")
        val samples = ArrayList<StoredSample>()
        val recordedAt = 1730000000000L
        for (condition in Conditions.all) {
            val tone = conditionTones.getValue(condition.id)
            val accelTone = accelTones.getValue(condition.id)
            for (group in 1..3) {
                val dir = File(root, condition.id + "/g" + String.format(Locale.US, "%02d", group))
                dir.mkdirs()
                val stem = "g" + String.format(Locale.US, "%02d", group) + "_" + condition.id
                val paths = SamplePaths.of(dir, stem)
                val pcmCount = (audioRate * audioSeconds).toInt()
                val pcm = ShortArray(pcmCount) { index ->
                    (0.4 * 32767.0 * sin(2.0 * PI * tone * index / audioRate)).toInt().toShort()
                }
                WavWriter(paths.audio, audioRate).use { it.write(pcm) }
                val accelSamples = ArrayList<AccelSample>()
                val base = 1_000_000_000L + group * 7L
                for (i in 0 until 300) {
                    val t = i / 200.0
                    accelSamples.add(
                        AccelSample(
                            tNs = base + i * 5_000_000L,
                            x = 3.0 + sin(2.0 * PI * accelTone * t),
                            y = 0.0,
                            z = 0.0,
                        )
                    )
                }
                AccelCsv.write(paths.accel, accelSamples, base, 200.0)
                val features = FeaturePipeline.extractFromFiles(paths.audio, paths.accel)
                FeaturesCsv.write(
                    file = paths.features,
                    features = features,
                    conditionId = condition.id,
                    conditionLabel = condition.label,
                    groupIndex = group,
                    fileStem = stem,
                    recordedAtMs = recordedAt + group,
                    durationS = audioSeconds,
                )
                samples.add(
                    StoredSample(
                        conditionId = condition.id,
                        conditionLabel = condition.label,
                        groupIndex = group,
                        paths = paths,
                        features = features,
                        durationS = audioSeconds,
                        recordedAtMs = recordedAt + group,
                    )
                )
            }
        }
        return samples
    }

    @Test
    fun featuresCsvAndDatasetIndexFollowContract() {
        val samples = buildDataset()
        assertEquals(15, samples.size)
        val table = CsvReader.readTable(samples.first().paths.features)
        assertEquals(38, table!!.header.size)
        assertEquals(1, table.rows.size)

        val indexFile = File(root, "dataset_index.csv")
        Reports.writeDatasetIndex(
            indexFile,
            "VD-20261118-001",
            Conditions.all.map { it.id to it.label },
            samples.groupingBy { it.conditionId }.eachCount(),
            Conditions.TARGET_GROUPS,
        )
        val index = CsvReader.readTable(indexFile)!!
        assertEquals(5, index.rows.size)
        val first = index.rows.first()
        assertEquals(3, index.long(first, "groups_collected"))
        assertEquals(0, index.long(first, "complete"))
    }

    @Test
    fun loocvSeparatesSyntheticConditionsPerfectly() {
        val samples = buildDataset()
        val items = samples.map { LabeledFeatures(it.conditionId, it.featureArray) }
        val result = ModelEvaluation.loocv(items, Conditions.ids)
        assertEquals(15, result.total)
        assertEquals(15, result.correct)
        assertEquals(1.0, result.accuracy, 1e-9)
        val matrix = result.confusionMatrix()
        for (i in matrix.indices) {
            for (j in matrix.indices) {
                assertEquals(if (i == j) 3 else 0, matrix[i][j])
            }
        }
    }

    @Test
    fun noiseEvaluationProducesContractTable() {
        val samples = buildDataset()
        val classifier = KnnClassifier(ModelSpec.K)
        classifier.fit(samples.map { LabeledFeatures(it.conditionId, it.featureArray) })
        val result = NoiseEvaluator.evaluate(
            samples = samples,
            classifier = classifier,
            conditionIds = Conditions.ids,
            snrLevels = listOf(20, 0),
            noiseTypes = listOf(NoiseType.WHITE, NoiseType.PINK),
            seedBase = 42L,
        )
        // 2 SNR x 2 noise types x 5 conditions
        assertEquals(20, result.rows.size)
        val csv = File(root, "noise_eval_VD-20261118-001.csv")
        Reports.writeNoiseEval(csv, "VD-20261118-001", result.rows, ModelSpec.DESCRIPTOR)
        val table = CsvReader.readTable(csv)!!
        assertEquals(9, table.header.size)
        assertEquals(20, table.rows.size)
        val parsed = Reports.readNoiseRows(csv)
        assertEquals(20, parsed.size)
        for (snr in listOf(20, 0)) {
            for (type in listOf(NoiseType.WHITE, NoiseType.PINK)) {
                val accuracy = result.accuracy(type, snr)
                assertTrue(accuracy.isFinite())
                assertTrue(accuracy >= 0.0 && accuracy <= 1.0)
            }
        }
    }

    @Test
    fun blindSessionPlanIsBalancedAndShuffled() {
        val session = BlindSession.create(java.util.Random(7L))
        assertEquals(15, session.total)
        val counts = (0 until session.total).mapNotNull { session.conditionForTrial(it) }
            .groupingBy { it }.eachCount()
        assertEquals(5, counts.size)
        for (condition in Conditions.all) assertEquals(3, counts[condition.id])
    }

    @Test
    fun featureVectorFromCsvCanBeClassified() {
        val samples = buildDataset()
        val reloaded = samples.map { FeaturesCsv.read(it.paths.features)!! }
        val classifier = KnnClassifier()
        classifier.fit(samples.mapIndexed { index, sample -> LabeledFeatures(sample.conditionId, FeatureVector.toArray(reloaded[index])) })
        val prediction = classifier.predict(FeatureVector.toArray(reloaded.first()))
        assertEquals(samples.first().conditionId, prediction!!.label)
        assertTrue(prediction.confidence > 0.99)
    }

    @Test
    fun samplePathsFollowDataDictionaryNaming() {
        val dir = File(folder.newFolder("session"), "C1_NORMAL_LOW/g01")
        val paths = SamplePaths.of(dir, "g01_C1_NORMAL_LOW")
        assertEquals("g01_C1_NORMAL_LOW_audio.wav", paths.audio.name)
        assertEquals("g01_C1_NORMAL_LOW_accel.csv", paths.accel.name)
        assertEquals("g01_C1_NORMAL_LOW_features.csv", paths.features.name)
        assertEquals("g01_C1_NORMAL_LOW_meta.json", paths.meta.name)
        assertEquals(dir, paths.audio.parentFile)
    }

}