package com.mobilelab.signalmap.data

import android.content.Context
import com.mobilelab.core.csv.CsvBuilder
import com.mobilelab.core.device.collectDeviceSnapshot
import com.mobilelab.core.device.deviceManifestJson
import com.mobilelab.core.files.Storage
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.signalmap.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * On-device file layout of one survey session:
 *
 *   <external-files>/experiments/signalmap/<stamp>_<sessionId>/
 *     manifest.json
 *     survey_<sessionId>.csv        # appended row by row -> crash safe
 *     points.csv                    # station catalogue snapshot
 *     scan_<sessionId>_<point>_r<n>.csv
 *     photos/<pointId>_<stamp>.jpg
 *     session.log
 *
 * Every write goes through this class so the data contract lives in one place.
 */
class SurveyRepository(private val context: Context) {

    data class SessionFiles(val sessionId: String, val dir: File) {
        val surveyCsv: File get() = File(dir, "survey_$sessionId.csv")
        val pointsCsv: File get() = File(dir, "points.csv")
        val manifestJson: File get() = File(dir, "manifest.json")
        val sessionLog: File get() = File(dir, "session.log")
        val photosDir: File get() = File(dir, "photos")
    }

    data class PhotoTarget(val file: File, val relativePath: String)

    private val appRoot: File get() = Storage.appRoot(context, DataContract.APP_TAG)

    /**
     * All file mutations are serialised: an async photo/manifest refresh must
     * never interleave with the append of a fresh measurement row.
     */
    private val writeLock = Any()

    // ------------------------------------------------------------------ session
    fun listExistingSessionIds(): List<String> {
        val regex = Regex("WS-\\d{8}-\\d{3}")
        return appRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { regex.find(it.name)?.value }
            ?: emptyList()
    }

    fun createSession(sessionId: String, points: List<SurveyPoint>, settings: AppSettings): SessionFiles {
        val dir = Storage.newSessionDir(context, DataContract.APP_TAG, sessionId)
        val files = SessionFiles(sessionId, dir)
        files.photosDir.mkdirs()
        LabLog.attach(files.sessionLog)
        LabLog.i(TAG, "session created: $sessionId at ${dir.absolutePath}")
        ensureSurveyHeader(files)
        writePoints(files, points)
        writeManifest(files, sessionId, System.currentTimeMillis(), points, emptyList(), SurveyProgress.EMPTY, settings, false)
        return files
    }

    fun openSession(sessionId: String, dirName: String): SessionFiles? {
        val dir = File(appRoot, dirName)
        if (!dir.isDirectory) return null
        val files = SessionFiles(sessionId, dir)
        files.photosDir.mkdirs()
        LabLog.attach(files.sessionLog)
        LabLog.i(TAG, "session reopened: $sessionId at ${dir.absolutePath}")
        ensureSurveyHeader(files)
        return files
    }

    fun loadRows(files: SessionFiles): List<SurveyRow> {
        val file = files.surveyCsv
        if (!file.isFile) return emptyList()
        return runCatching {
            CsvCodec.parseSurvey(file.readText(StandardCharsets.UTF_8))
        }.onFailure { LabLog.e(TAG, "failed to parse ${file.name}", it) }
            .getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------- writes
    private fun ensureSurveyHeader(files: SessionFiles) {
        val file = files.surveyCsv
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(CsvCodec.surveyHeaderLine(), StandardCharsets.UTF_8)
        }
    }

    /**
     * Appends exactly one data row. The row is formatted before any file handle
     * is opened, so a process kill can never leave a half written line in a
     * position where the parser would merge it with the next row.
     */
    fun appendRow(files: SessionFiles, row: SurveyRow) = synchronized(writeLock) {
        ensureSurveyHeader(files)
        val line = CsvCodec.surveyDataLine(row, hashBssid = false)
        files.surveyCsv.appendText(line, StandardCharsets.UTF_8)
        LabLog.i(TAG, "row written ${row.pointId} r${row.repeatIndex} (row ${loadRows(files).size})")
    }

    /**
     * Rewrites only the photo_file column of one station. Reads the table back
     * from disk under the write lock so a row appended by a concurrent
     * measurement is never lost. Returns the number of updated rows.
     */
    fun updateRowPhoto(files: SessionFiles, pointId: String, photoFile: String): Int =
        synchronized(writeLock) {
            val current = loadRows(files)
            if (current.isEmpty()) return@synchronized 0
            val updated = current.map { row ->
                if (row.pointId == pointId) row.copy(photoFile = photoFile) else row
            }
            files.surveyCsv.writeText(CsvCodec.surveyCsv(updated, hashBssid = false), StandardCharsets.UTF_8)
            val touched = updated.count { it.pointId == pointId }
            LabLog.i(TAG, "photo_file updated for $pointId in $touched rows (total ${updated.size})")
            touched
        }

    /** Full rewrite used by tests/tools; the app prefers targeted updates. */
    fun rewriteSurvey(files: SessionFiles, rows: List<SurveyRow>) = synchronized(writeLock) {
        files.surveyCsv.writeText(CsvCodec.surveyCsv(rows, hashBssid = false), StandardCharsets.UTF_8)
        LabLog.i(TAG, "survey table rewritten: ${rows.size} rows")
    }

    fun writePoints(files: SessionFiles, points: List<SurveyPoint>) = synchronized(writeLock) {
        files.pointsCsv.writeText(CsvCodec.pointsCsv(points), StandardCharsets.UTF_8)
    }

    fun writeScan(files: SessionFiles, pointId: String, repeat: Int, entries: List<ScanEntry>) =
        synchronized(writeLock) {
            val file = File(files.dir, "scan_${files.sessionId}_${pointId}_r$repeat.csv")
            file.writeText(CsvCodec.scanCsv(entries, hashBssid = false), StandardCharsets.UTF_8)
        }

    fun scanFiles(files: SessionFiles): List<File> =
        files.dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("scan_") && it.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun writeManifest(
        files: SessionFiles,
        sessionId: String,
        startedAtMs: Long,
        points: List<SurveyPoint>,
        rows: List<SurveyRow>,
        progress: SurveyProgress,
        settings: AppSettings,
        hashApplied: Boolean,
    ) = synchronized(writeLock) {
        files.manifestJson.writeText(
            buildManifest(
                dirName = files.dir.name,
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                points = points,
                rows = rows,
                progress = progress,
                settings = settings,
                hashApplied = hashApplied,
            ),
            StandardCharsets.UTF_8,
        )
    }

    // ------------------------------------------------------------------ photos
    /** Creates photos/<pointId>_<stamp>.jpg and returns it with its CSV relative path. */
    fun newPhotoFile(files: SessionFiles, pointId: String, epochMs: Long): PhotoTarget {
        files.photosDir.mkdirs()
        val base = "${pointId}_${TimeFmt.fileStamp(epochMs)}"
        var candidate = File(files.photosDir, "$base.jpg")
        var index = 2
        while (candidate.exists()) {
            candidate = File(files.photosDir, "${base}_$index.jpg")
            index++
        }
        return PhotoTarget(candidate, "photos/${candidate.name}")
    }

    fun photoCount(files: SessionFiles): Int =
        files.photosDir.listFiles()?.count { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) } ?: 0

    // ------------------------------------------------------------------ export
    /**
     * Builds the ZIP described by DATA_DICTIONARY 2.4. The working directory on
     * disk keeps raw BSSIDs (so a lost phone can still be audited); hashing is
     * applied to the exported copy only.
     */
    fun buildExportZip(
        files: SessionFiles,
        rows: List<SurveyRow>,
        points: List<SurveyPoint>,
        progress: SurveyProgress,
        settings: AppSettings,
        startedAtMs: Long,
        hashBssid: Boolean,
    ): File {
        val stamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val staging = File(context.cacheDir, "signalmap_export/${files.sessionId}_$stamp")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        try {
            // survey table
            File(staging, "survey_${files.sessionId}.csv")
                .writeText(CsvCodec.surveyCsv(rows, hashBssid), StandardCharsets.UTF_8)
            // station catalogue
            File(staging, "points.csv").writeText(CsvCodec.pointsCsv(points), StandardCharsets.UTF_8)
            // scan detail
            scanFiles(files).forEach { src ->
                File(staging, src.name).writeText(
                    rewriteScanCsv(src.readText(StandardCharsets.UTF_8), hashBssid),
                    StandardCharsets.UTF_8,
                )
            }
            // photos
            val photosOut = File(staging, "photos")
            photosOut.mkdirs()
            files.photosDir.listFiles()?.filter { it.isFile }?.forEach { photo ->
                photo.copyTo(File(photosOut, photo.name), overwrite = true)
            }
            // session log
            if (files.sessionLog.isFile) {
                files.sessionLog.copyTo(File(staging, "session.log"), overwrite = true)
            }
            // manifest regenerated with export metadata
            File(staging, "manifest.json").writeText(
                buildManifest(
                    dirName = files.dir.name,
                    sessionId = files.sessionId,
                    startedAtMs = startedAtMs,
                    points = points,
                    rows = rows,
                    progress = progress,
                    settings = settings,
                    hashApplied = hashBssid,
                ),
                StandardCharsets.UTF_8,
            )

            val zip = File(appRoot, "${files.sessionId}_export_$stamp.zip")
            Storage.zipDirectory(staging, zip)
            LabLog.i(TAG, "export zip built: ${zip.name} (${Storage.humanBytes(zip.length())})")
            return zip
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Single CSV export (hashed on request) written to the cache for the share sheet. */
    fun buildSurveyCsvExport(files: SessionFiles, rows: List<SurveyRow>, hashBssid: Boolean): File {
        val stamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val out = File(context.cacheDir, "signalmap_export/survey_${files.sessionId}_$stamp.csv")
        out.parentFile?.mkdirs()
        out.writeText(CsvCodec.surveyCsv(rows, hashBssid), StandardCharsets.UTF_8)
        return out
    }

    /** Re-serialises a stored scan file through the contract header (drops unknown columns). */
    private fun rewriteScanCsv(text: String, hashBssid: Boolean): String {
        val table = CsvText.parse(text)
        if (table.isEmpty()) return CsvCodec.scanCsv(emptyList(), hashBssid)
        val header = table.first()
        val builder = CsvBuilder(DataContract.SCAN_HEADER)
        table.drop(1).filter { it.any { cell -> cell.isNotBlank() } }.forEach { cells ->
            fun cell(name: String): String {
                val index = header.indexOf(name)
                return if (index in cells.indices) cells[index] else ""
            }
            val bssid = cell("bssid")
            builder.row(
                cell("timestamp_iso"),
                if (hashBssid && bssid.isNotEmpty()) BssidHash.hash(bssid) else bssid,
                cell("ssid"),
                cell("frequency_mhz"),
                cell("channel"),
                cell("rssi_dbm"),
                cell("link_speed_mbps"),
                cell("capabilities"),
            )
        }
        return builder.build()
    }

    // ---------------------------------------------------------------- manifest
    private fun buildManifest(
        dirName: String,
        sessionId: String,
        startedAtMs: Long,
        points: List<SurveyPoint>,
        rows: List<SurveyRow>,
        progress: SurveyProgress,
        settings: AppSettings,
        hashApplied: Boolean,
    ): String {
        val snapshot = collectDeviceSnapshot(context)
        val extras = LinkedHashMap<String, Any?>()
        extras["sessionId"] = sessionId
        extras["sessionDir"] = dirName
        extras["sessionStartedAt"] = startedAtMs
        extras["survey"] = com.mobilelab.core.json.JsonObject()
            .put("points", points.size)
            .put("repeatsPerPoint", DataContract.REPEATS_PER_POINT)
            .put("rowsTarget", DataContract.TOTAL_ROWS)
            .put("rowsWritten", rows.size)
            .put("completedRepeats", progress.totalCompleted())
            .put("photoCount", rows.map { it.photoFile }.filter { it.isNotEmpty() }.distinct().size)
        extras["protocol"] = com.mobilelab.core.json.JsonObject()
            .put("httpTestUrl", settings.httpTestUrl)
            .put("tcpTarget", settings.tcpTarget)
            .put("rttAttempts", settings.rttAttempts)
            .put("rttTimeoutMs", settings.tcpTimeoutMs)
            .put("httpAttempts", settings.httpAttempts)
            .put("gatewayAttempts", settings.gatewayAttempts)
            .put("repeatIntervalSec", AppSettings.REPEAT_INTERVAL_SEC)
            .put("throughputEnabled", settings.throughputEnabled)
            .put("throughputBytes", settings.throughputBytes)
        extras["bssidHash"] = com.mobilelab.core.json.JsonObject()
            .put("applied", hashApplied)
            .put("algorithm", if (hashApplied) BssidHash.ALGORITHM_NAME else "")
        extras["points"] = points.map { point ->
            com.mobilelab.core.json.JsonObject()
                .put("point_id", point.id)
                .put("point_name", point.name)
                .put("point_type", point.type.id)
                .put("wall_material", point.wallMaterial.id)
                .put("obstruction", point.obstruction)
                .put("distance_m", point.distanceM)
                .put("floor", point.floor)
                .put("photo_file", point.photoFile)
                .put("repeats_done", progress.repeats(point.id))
        }
        return deviceManifestJson(
            appLabel = DataContract.APP_LABEL,
            appVersion = BuildConfig.VERSION_NAME,
            projectCode = DataContract.PROJECT_CODE,
            snapshot = snapshot,
            extras = extras,
        )
    }

    companion object {
        private const val TAG = "SurveyRepository"
    }
}