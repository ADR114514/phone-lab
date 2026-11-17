package com.mobilelab.vibrodiag.data

import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.features.FeaturePipeline
import com.mobilelab.vibrodiag.features.WavIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** CSV row counts, quoting, feature serialisation and accel CSV contract. */
class CsvAndFeatureFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun csvReaderHandlesQuotedFieldsAndBom() {
        val file = folder.newFile("quoted.csv")
        file.writeText("\uFEFFa,b,c\r\n1,\"x,y\",3\r\n4,\"he said \"\"hi\"\"\",6\r\n")
        val table = CsvReader.readTable(file)
        assertNotNull(table)
        assertEquals(listOf("a", "b", "c"), table!!.header)
        assertEquals(2, table.rows.size)
        assertEquals("x,y", table.string(table.rows[0], "b"))
        assertEquals("he said \"hi\"", table.string(table.rows[1], "b"))
        assertEquals(4L, table.long(table.rows[1], "a"))
    }

    @Test
    fun featuresCsvRoundTripsAllColumnsAndWritesBlankForNaN() {
        val file = folder.newFile("g01_C1_NORMAL_LOW_features.csv")
        val features = LinkedHashMap<String, Double>()
        for ((index, name) in FeatureNames.numeric.withIndex()) {
            features[name] = index.toDouble() * 0.5
        }
        features["audio_flatness"] = Double.NaN
        features["accel_peak_hz"] = 25.125
        FeaturesCsv.write(
            file = file,
            features = features,
            conditionId = "C1_NORMAL_LOW",
            conditionLabel = "风扇低档正常",
            groupIndex = 1,
            fileStem = "g01_C1_NORMAL_LOW",
            recordedAtMs = 1730000000000L,
            durationS = 15.0,
        )
        val text = file.readText()
        val lines = text.trim().split("\r\n")
        assertEquals(2, lines.size)
        val header = lines[0].removePrefix("\uFEFF").split(",")
        assertEquals(FeatureNames.csvHeader, header)
        assertEquals(38, header.size)
        assertTrue(lines[1].contains(",,"))

        val table = CsvReader.readTable(file)
        assertNotNull(table)
        val row = table!!.rows.first()
        assertEquals("C1_NORMAL_LOW", table.string(row, "condition_id"))
        assertEquals("风扇低档正常", table.string(row, "condition_label"))
        assertEquals(1, table.long(row, "group_index"))
        assertEquals(1730000000000L, table.long(row, "recorded_at_ms"))
        assertEquals(25.125, table.double(row, "accel_peak_hz"), 1e-9)

        val loaded = FeaturesCsv.read(file)
        assertNotNull(loaded)
        assertTrue(loaded!!.getValue("audio_flatness").isNaN())
        assertEquals(25.125, loaded.getValue("accel_peak_hz"), 1e-6)
        assertEquals(features.getValue("audio_rms_dbfs")!!, loaded.getValue("audio_rms_dbfs"), 1e-6)
    }

    @Test
    fun accelCsvRoundTripsAndDerivesSampleRate() {
        val file = folder.newFile("g01_C1_NORMAL_LOW_accel.csv")
        val base = 1_000_000_000L
        val samples = ArrayList<AccelSample>()
        for (i in 0 until 200) {
            samples.add(AccelSample(base + i * 5_000_000L, 0.01 * i, -0.02 * i, AccelCsv.GRAVITY))
        }
        AccelCsv.write(file, samples, base, 200.0)
        val table = CsvReader.readTable(file)
        assertNotNull(table)
        assertEquals(AccelCsv.header, table!!.header)
        assertEquals(200, table.rows.size)
        assertEquals(0.0, table.double(table.rows.first(), "t_ms"), 1e-9)
        assertEquals(995.0, table.double(table.rows.last(), "t_ms"), 1e-6)
        assertEquals(1.0, table.double(table.rows.first(), "mag_g"), 1e-6)
        assertEquals(200.0, table.double(table.rows.first(), "sample_rate_hz"), 1e-9)

        val series = AccelCsv.read(file)
        assertEquals(200, series.size)
        assertEquals(200.0, series.sampleRateHz, 1e-6)
        assertEquals(0.01 * 199, series.x[199], 1e-6)
    }

    @Test
    fun pipelineExtractsAudioAndAccelFeaturesFromFiles() {
        val wavFile = folder.newFile("g01_C1_NORMAL_LOW_audio.wav")
        val sampleRate = 44100
        val count = sampleRate / 2
        val pcm = ShortArray(count) { index ->
            (0.4 * 32767.0 * sin(2.0 * PI * 1000.0 * index / sampleRate)).toInt().toShort()
        }
        com.mobilelab.core.signal.WavWriter(wavFile, sampleRate).use { it.write(pcm) }

        val accelFile = folder.newFile("g01_C1_NORMAL_LOW_accel.csv")
        val base = 5_000_000_000L
        val samples = ArrayList<AccelSample>()
        for (i in 0 until 3000) {
            val t = i / 200.0
            samples.add(AccelSample(base + i * 5_000_000L, 3.0 + sin(2.0 * PI * 20.0 * t), 0.0, 0.0))
        }
        AccelCsv.write(accelFile, samples, base, 200.0)

        val features = FeaturePipeline.extractFromFiles(wavFile, accelFile)
        assertEquals(FeatureNames.count, features.size)
        assertEquals(1000.0, features.getValue("audio_peak_hz"), 2.0)
        assertEquals(20.0, features.getValue("accel_peak_hz"), 0.6)
        assertTrue(abs(features.getValue("audio_rms_dbfs") - 20.0 * kotlin.math.log10(0.4 / kotlin.math.sqrt(2.0))) < 0.2)
        val wav = WavIo.read(wavFile)
        assertEquals(count, wav.samples.size)
    }
}
