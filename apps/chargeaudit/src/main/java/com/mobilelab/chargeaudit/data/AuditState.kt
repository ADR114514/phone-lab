package com.mobilelab.chargeaudit.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.service.Reminder
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.ChargeSummary
import com.mobilelab.chargeaudit.model.Nameplate
import com.mobilelab.core.files.Storage
import com.mobilelab.core.time.TimeFmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 电池状态快照（保存采样点时自动读取）。 */
data class BatteryReading(
    val pct: Int?,
    val voltageMv: Int?,
    val tempC: Double?,
    val chargeUah: Long?,
    val currentUa: Long?,
    val charging: Boolean,
)

object BatteryReader {
    fun read(context: Context): BatteryReading {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        fun prop(id: Int): Long? = runCatching { manager.getLongProperty(id) }
            .getOrNull()?.takeIf { it != Long.MIN_VALUE }
        val battery = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val voltage = battery?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val tempRaw = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (tempRaw != Int.MIN_VALUE) tempRaw / 10.0 else null
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        return BatteryReading(
            pct = pct,
            voltageMv = voltage,
            tempC = temp,
            chargeUah = prop(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentUa = prop(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            charging = charging,
        )
    }
}

/**
 * 会话状态与持久化：prefs 里存 JSON，CSV 每采一点就追加，进程被杀后可恢复。
 */
class AuditState(private val context: Context) {

    companion object {
        const val PREFS = "chargeaudit_state"
        const val KEY_STATE = "state_json"
        const val INTERVAL_MS = 10 * 60 * 1000L
        const val CSV_HEADER =
            "point_index,elapsed_min,timestamp_iso,usb_voltage_v,usb_current_a,usb_power_w," +
                "phone_battery_pct,phone_voltage_mv,phone_charge_counter_uah,phone_battery_temp_c,charging,note"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var nameplate by androidx.compose.runtime.mutableStateOf(Nameplate())
        private set
    var points by androidx.compose.runtime.mutableStateOf<List<AuditPoint>>(emptyList())
        private set
    var sessionId by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var sessionDir by androidx.compose.runtime.mutableStateOf<File?>(null)
        private set
    var startMs by androidx.compose.runtime.mutableStateOf(0L)
        private set
    var nextDueMs by androidx.compose.runtime.mutableStateOf(0L)
        private set
    var active by androidx.compose.runtime.mutableStateOf(false)
        private set
    var screen by androidx.compose.runtime.mutableStateOf(com.mobilelab.chargeaudit.model.Screen.HOME)
    var message by androidx.compose.runtime.mutableStateOf<String?>(null)

    init {
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY_STATE, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            val n = root.optJSONObject("nameplate")
            if (n != null) {
                nameplate = Nameplate(
                    model = n.optString("model", "Huawei CP11QC"),
                    ratedEnergyWh = n.optDouble("ratedEnergyWh", 34.49),
                    ratedOutputMah = n.optDouble("ratedOutputMah", 6200.0),
                    ratedOutputV = n.optDouble("ratedOutputV", 5.0),
                    cellTypicalMah = n.optDouble("cellTypicalMah", 10000.0),
                    cellRatedMah = n.optDouble("cellRatedMah", 9500.0),
                    phoneCapacityMah = n.optDouble("phoneCapacityMah", 5400.0),
                    phoneNominalV = n.optDouble("phoneNominalV", 3.85),
                    testerModel = n.optString("testerModel", ""),
                )
            }
            sessionId = root.optString("sessionId", "").takeIf { it.isNotBlank() }
            val dir = root.optString("sessionDir", "").takeIf { it.isNotBlank() }
            sessionDir = dir?.let { File(it) }
            startMs = root.optLong("startMs", 0L)
            nextDueMs = root.optLong("nextDueMs", 0L)
            active = root.optBoolean("active", false)
            val array = root.optJSONArray("points") ?: JSONArray()
            points = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        AuditPoint(
                            index = item.optInt("index", index),
                            timestampMs = item.optLong("timestampMs", 0L),
                            elapsedMin = item.optDouble("elapsedMin", 0.0),
                            usbVoltageV = item.optDouble("usbVoltageV", 0.0),
                            usbCurrentA = item.optDouble("usbCurrentA", 0.0),
                            testerWhCum = item.optDouble("testerWhCum", Double.NaN).takeIf { it.isFinite() },
                            phonePct = item.optInt("phonePct", -1).takeIf { it >= 0 },
                            phoneVoltageMv = item.optInt("phoneVoltageMv", -1).takeIf { it > 0 },
                            phoneChargeUah = item.optLong("phoneChargeUah", -1L).takeIf { it >= 0 },
                            phoneTempC = item.optDouble("phoneTempC", Double.NaN).takeIf { it.isFinite() },
                            charging = item.optBoolean("charging", true),
                            note = item.optString("note", ""),
                        ),
                    )
                }
            }
        }.onFailure { message = "读取上次会话失败：" + it.message }
    }

    private fun save() {
        val root = JSONObject()
        root.put("nameplate", JSONObject().apply {
            put("model", nameplate.model)
            put("ratedEnergyWh", nameplate.ratedEnergyWh)
            put("ratedOutputMah", nameplate.ratedOutputMah)
            put("ratedOutputV", nameplate.ratedOutputV)
            put("cellTypicalMah", nameplate.cellTypicalMah)
            put("cellRatedMah", nameplate.cellRatedMah)
            put("phoneCapacityMah", nameplate.phoneCapacityMah)
            put("phoneNominalV", nameplate.phoneNominalV)
            put("testerModel", nameplate.testerModel)
        })
        root.put("sessionId", sessionId ?: "")
        root.put("sessionDir", sessionDir?.absolutePath ?: "")
        root.put("startMs", startMs)
        root.put("nextDueMs", nextDueMs)
        root.put("active", active)
        root.put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().apply {
                    put("index", point.index)
                    put("timestampMs", point.timestampMs)
                    put("elapsedMin", point.elapsedMin)
                    put("usbVoltageV", point.usbVoltageV)
                    put("usbCurrentA", point.usbCurrentA)
                    point.testerWhCum?.let { put("testerWhCum", it) }
                    point.phonePct?.let { put("phonePct", it) }
                    point.phoneVoltageMv?.let { put("phoneVoltageMv", it) }
                    point.phoneChargeUah?.let { put("phoneChargeUah", it) }
                    point.phoneTempC?.let { put("phoneTempC", it) }
                    put("charging", point.charging)
                    put("note", point.note)
                })
            }
        })
        prefs.edit { putString(KEY_STATE, root.toString()) }
    }

    fun saveNameplate(updated: Nameplate) {
        nameplate = updated
        save()
        message = "铭牌参数已保存"
    }

    fun startSession() {
        if (active) return
        val id = "CA-" + TimeFmt.fileStamp(System.currentTimeMillis())
        val dir = Storage.newSessionDir(context, "chargeaudit", id)
        sessionId = id
        sessionDir = dir
        startMs = System.currentTimeMillis()
        nextDueMs = startMs + INTERVAL_MS
        points = emptyList()
        active = true
        Storage.writeText(File(dir, "chargeaudit_$id.csv"), CSV_HEADER + "\r\n")
        writeMeta()
        save()
        Reminder.schedule(context, nextDueMs)
        message = "新会话已开始：每 10 分钟记录一次"
        screen = com.mobilelab.chargeaudit.model.Screen.RECORD
    }

    fun addPoint(usbV: Double, usbA: Double, testerWh: Double?, note: String, battery: BatteryReading): String? {
        if (!active) return "会话未开始"
        if (usbV <= 0.0 || !usbV.isFinite()) return "电压必须大于 0"
        if (usbA < 0.0 || !usbA.isFinite()) return "电流不能为负"
        val now = System.currentTimeMillis()
        val elapsed = (now - startMs) / 60000.0
        val point = AuditPoint(
            index = points.size,
            timestampMs = now,
            elapsedMin = elapsed,
            usbVoltageV = usbV,
            usbCurrentA = usbA,
            testerWhCum = testerWh,
            phonePct = battery.pct,
            phoneVoltageMv = battery.voltageMv,
            phoneChargeUah = battery.chargeUah,
            phoneTempC = battery.tempC,
            charging = battery.charging,
            note = note,
        )
        points = points + point
        appendCsv(point)
        nextDueMs = now + INTERVAL_MS
        save()
        Reminder.schedule(context, nextDueMs)
        message = "第 " + (point.index + 1) + " 点已保存（" + ChargeMath.fmt(point.powerW, 2) + " W）"
        return null
    }

    fun deleteLastPoint() {
        if (points.isEmpty()) return
        points = points.dropLast(1)
        rewriteCsv()
        nextDueMs = System.currentTimeMillis() + INTERVAL_MS
        save()
        Reminder.schedule(context, nextDueMs)
        message = "已删除最后一个采样点"
    }

    fun finishSession() {
        active = false
        Reminder.cancel(context)
        save()
        screen = com.mobilelab.chargeaudit.model.Screen.RESULTS
        message = "会话已结束，可导出数据/查看效率"
    }

    fun resetSession(deleteFiles: Boolean) {
        Reminder.cancel(context)
        if (deleteFiles) sessionDir?.let { runCatching { it.deleteRecursively() } }
        points = emptyList()
        sessionId = null
        sessionDir = null
        startMs = 0L
        nextDueMs = 0L
        active = false
        save()
        screen = com.mobilelab.chargeaudit.model.Screen.HOME
        message = if (deleteFiles) "已删除本会话数据" else "已清空当前会话（保留磁盘文件）"
    }

    fun summary(): ChargeSummary? = if (points.size >= 2) ChargeMath.summarize(points, nameplate) else null

    fun exportZip(): File? {
        val dir = sessionDir ?: return null
        val id = sessionId ?: return null
        val stamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val staging = File(context.cacheDir, "chargeaudit_export_" + stamp)
        staging.mkdirs()
        val csv = csvFile() ?: return null
        runCatching { csv.copyTo(File(staging, "chargeaudit_$id.csv"), overwrite = true) }
        runCatching {
            val meta = JSONObject().apply {
                put("schema", "phonelab.charge/1")
                put("project", "07-charge-bank-efficiency")
                put("sessionId", id)
                put("createdAt", TimeFmt.isoLocal(startMs))
                put("nameplate", JSONObject().apply {
                    put("model", nameplate.model)
                    put("ratedEnergyWh", nameplate.ratedEnergyWh)
                    put("ratedOutputMah", nameplate.ratedOutputMah)
                    put("ratedOutputV", nameplate.ratedOutputV)
                    put("cellTypicalMah", nameplate.cellTypicalMah)
                    put("cellRatedMah", nameplate.cellRatedMah)
                    put("phoneCapacityMah", nameplate.phoneCapacityMah)
                    put("phoneNominalV", nameplate.phoneNominalV)
                    put("testerModel", nameplate.testerModel)
                })
                put("points", points.size)
            }
            Storage.writeText(File(staging, "manifest.json"), meta.toString(2))
        }
        summary()?.let { summary ->
            runCatching {
                val json = JSONObject().apply {
                    put("points", summary.points)
                    put("duration_h", summary.durationH)
                    put("avg_power_w", summary.avgPowerW)
                    put("peak_power_w", summary.peakPowerW)
                    put("e_out_wh", summary.eOutWh)
                    put("e_phone_wh", summary.ePhoneWh)
                    put("phone_energy_method", summary.phoneEnergyMethod)
                    put("eta_output", summary.etaOutput)
                    put("eta_charge", summary.etaCharge)
                    put("eta_total", summary.etaTotal)
                    put("equiv_output_mah", summary.equivOutputMah)
                    put("loss_boost_wh", summary.lossBoostWh)
                    put("loss_charge_wh", summary.lossChargeWh)
                    put("loss_total_wh", summary.lossTotalWh)
                }
                Storage.writeText(File(staging, "summary.json"), json.toString(2))
            }
        }
        val zip = File(context.cacheDir, "chargeaudit_" + id + "_" + stamp + ".zip")
        runCatching { Storage.zipDirectory(staging, zip) }
        staging.deleteRecursively()
        return zip.takeIf { it.isFile }
    }

    fun csvFile(): File? {
        val dir = sessionDir ?: return null
        val id = sessionId ?: return null
        return File(dir, "chargeaudit_$id.csv")
    }

    private fun appendCsv(point: AuditPoint) {
        val file = csvFile() ?: return
        Storage.appendText(file, csvRow(point) + "\r\n")
    }

    private fun rewriteCsv() {
        val file = csvFile() ?: return
        val builder = StringBuilder(CSV_HEADER).append("\r\n")
        points.forEach { builder.append(csvRow(it)).append("\r\n") }
        Storage.writeText(file, builder.toString())
    }

    private fun csvRow(point: AuditPoint): String = String.format(
        Locale.US,
        "%d,%.1f,%s,%.3f,%.3f,%.3f,%s,%s,%s,%s,%d,%s",
        point.index,
        point.elapsedMin,
        TimeFmt.isoLocal(point.timestampMs),
        point.usbVoltageV,
        point.usbCurrentA,
        point.powerW,
        point.phonePct?.toString() ?: "",
        point.phoneVoltageMv?.toString() ?: "",
        point.phoneChargeUah?.toString() ?: "",
        point.phoneTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        if (point.charging) 1 else 0,
        point.note.replace(",", "，").replace("\n", " "),
    )

    private fun writeMeta() {
        val dir = sessionDir ?: return
        val id = sessionId ?: return
        runCatching {
            val meta = JSONObject().apply {
                put("schema", "phonelab.charge/1")
                put("project", "07-charge-bank-efficiency")
                put("sessionId", id)
                put("createdAt", TimeFmt.isoLocal(startMs))
                put("nameplate", JSONObject().apply {
                    put("model", nameplate.model)
                    put("ratedEnergyWh", nameplate.ratedEnergyWh)
                    put("ratedOutputMah", nameplate.ratedOutputMah)
                    put("ratedOutputV", nameplate.ratedOutputV)
                })
            }
            Storage.writeText(File(dir, "manifest.json"), meta.toString(2))
        }
    }
}
