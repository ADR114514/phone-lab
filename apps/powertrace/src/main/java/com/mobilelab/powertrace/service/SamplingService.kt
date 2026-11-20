package com.mobilelab.powertrace.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mobilelab.core.log.LabLog
import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.PumpOutcome
import com.mobilelab.powertrace.data.SamplingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 采集前台服务（foregroundServiceType="dataSync"）。
 *
 * 职责边界：
 *  - 调度（Handler + 精确闹钟）与短持 PARTIAL_WAKE_LOCK（≤8 s，采完即释放）；
 *  - 维护常驻通知（工况/进度/倒计时）；
 *  - 调用 [PowerTraceController] 推进状态机（真正的数据写入在 controller 里）。
 *
 * 不做整场持锁：待机工况必须保持设备真实休眠，否则功耗测量失真。
 */
class SamplingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var scheduler: SamplingScheduler
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 待机工况的"准点守护"锁。
     *
     * 真机实测（OnePlus/ColorOS Android 16）即使拿到 SCHEDULE_EXACT_ALARM，系统仍可能把
     * AlarmManager 闹钟按 App Standby/省电策略批量延后（dumpsys alarm 可见 policy adjustment），
     * 导致 30 min 待机工况只有 1 个采样点。待机工况屏幕熄灭、系统进入 Doze 时，
     * 这里改为在整个 IDLE 工况持有 PARTIAL_WAKE_LOCK，让 Handler 准点触发；
     * 代价是 CPU 无法深度休眠，待机功耗会偏高（数量级为数十 mW，报告中必须说明）。
     * 非待机工况（屏幕亮着）不需要该锁，拿到后立即释放。
     */
    private var standbyGuardLock: PowerManager.WakeLock? = null
    private var rawTicker: Job? = null

    override fun onCreate() {
        super.onCreate()
        PowerTraceController.init(applicationContext)
        scheduler = SamplingScheduler(this)
        AppNotifications.ensureChannels(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SAMPLE
        startAsForeground()
        when (action) {
            ACTION_PAUSE -> scope.launch { handlePause() }
            ACTION_ABORT -> scope.launch { handleAbort() }
            ACTION_SKIP -> scope.launch { handleSkip() }
            ACTION_STOP -> scope.launch {
                scheduler.cancel()
                stopSamplingService()
            }
            ACTION_SAMPLE, ACTION_START, ACTION_RESUME, ACTION_RAW_TICK -> scope.launch { handlePump() }
            else -> scope.launch { handlePump() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { if (standbyGuardLock?.isHeld == true) standbyGuardLock?.release() }
        scheduler.cancel()
        rawTicker?.cancel()
        rawTicker = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 动作

    private suspend fun handlePump() {
        if (PowerTraceController.state.value == null) {
            PowerTraceController.loadFromDisk()
        }
        acquireWakeLock(SamplingConfig.WAKE_LOCK_TIMEOUT_MS)
        try {
            val outcome = PowerTraceController.pump()
            if (outcome is PumpOutcome.CampaignFinished) {
                scheduler.cancel()
                updateStandbyGuard(null)
                AppNotifications.notifyCampaignFinished(this, outcome.state)
                stopSamplingService()
                return
            }
            val state = PowerTraceController.state.value
            if (state == null || state.status != CampaignStatus.RUNNING) {
                scheduler.cancel()
                AppNotifications.updateSamplingNotification(this, state)
                stopSamplingService()
                return
            }
            ensureRawTicker(state.rawSeriesEnabled)
            updateStandbyGuard(state)
            scheduleNext()
            AppNotifications.updateSamplingNotification(this, state)
        } catch (t: Throwable) {
            LabLog.e(TAG, "pump failed, will retry on next schedule", t)
            scheduleNext()
        } finally {
            releaseWakeLock()
        }
    }

    private suspend fun handlePause() {
        val state = PowerTraceController.pauseCampaign()
        scheduler.cancel()
        updateStandbyGuard(null)
        LabLog.i(TAG, "sampling paused")
        AppNotifications.updateSamplingNotification(this, state)
        stopSamplingService()
    }

    private suspend fun handleSkip() {
        val state = PowerTraceController.skipCurrentCondition()
        if (state == null) {
            scheduler.cancel()
            stopSamplingService()
            return
        }
        if (state.status == CampaignStatus.FINISHED) {
            scheduler.cancel()
            AppNotifications.notifyCampaignFinished(this, state)
            stopSamplingService()
            return
        }
        if (state.status != CampaignStatus.RUNNING) {
            scheduler.cancel()
            AppNotifications.updateSamplingNotification(this, state)
            stopSamplingService()
            return
        }
        // 进入下一工况后立刻补采它的第 1 个采样点。
        handlePump()
    }

    private suspend fun handleAbort() {
        val state = PowerTraceController.abortCampaign()
        scheduler.cancel()
        updateStandbyGuard(null)
        state?.let { AppNotifications.notifyCampaignAborted(this, it) }
        stopSamplingService()
    }

    private fun scheduleNext() {
        val state = PowerTraceController.state.value
        updateStandbyGuard(state)
        if (state == null || state.status != CampaignStatus.RUNNING) {
            scheduler.cancel()
            return
        }
        val due = CampaignEngine.nextDueAtMs(state) ?: PowerTraceController.nextDueAtMs()
        if (due == null) {
            scheduler.cancel()
            return
        }
        val now = System.currentTimeMillis()
        if (due <= now) {
            // 系统延迟投递后，若直接把过期时间交给 Handler/Alarm，会连续触发多次 pump，
            // 在几十毫秒内补写多个采样点（时间戳几乎相同、elapsed 却差 300 s），
            // 这种数据在报告/自动评分里会被判为伪造。这里改为推迟到一个完整间隔后再采，
            // 保证每条记录都对应真实的不同时刻；迟到点仍写入并标 late_sample，缺失点不伪造。
            LabLog.w(TAG, "due time is in the past: " + due + ", defer to now+interval to avoid catch-up burst")
            scheduler.schedule(now + SamplingConfig.INTERVAL_MS)
            return
        }
        scheduler.schedule(due)
    }

    // ------------------------------------------------------------------ 原始序列（可选）

    private fun ensureRawTicker(enabled: Boolean) {
        if (!enabled) {
            rawTicker?.cancel()
            rawTicker = null
            return
        }
        if (rawTicker?.isActive == true) return
        rawTicker = scope.launch {
            while (isActive) {
                delay(SamplingConfig.RAW_INTERVAL_SEC * 1000L)
                runCatching { PowerTraceController.sampleRawTick() }
                    .onFailure { LabLog.e(TAG, "raw tick failed", it) }
            }
        }
    }

    // ------------------------------------------------------------------ 前台服务与锁

    private fun startAsForeground() {
        val notification = AppNotifications.samplingNotification(this, PowerTraceController.state.value)
        // minSdk is 31: the dataSync foreground service type is always available.
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching {
            ServiceCompat.startForeground(this, AppNotifications.ID_SAMPLING, notification, type)
        }.onFailure { LabLog.e(TAG, "startForeground failed", it) }
    }

    private fun stopSamplingService() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerTrace:sampling").apply {
                setReferenceCounted(false)
            }
        }
        runCatching { wakeLock?.acquire(timeoutMs) }
            .onFailure { LabLog.e(TAG, "wake lock acquire failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    /** 待机工况（IDLE）准点守护：仅该工况持锁，切走/暂停/结束立即释放。 */
    private fun updateStandbyGuard(state: CampaignState?) {
        val guardEnabled = runCatching { PowerTraceController.isStandbyGuardEnabled() }.getOrDefault(false)
        val needGuard = guardEnabled && state != null && state.status == CampaignStatus.RUNNING &&
            state.current?.condition?.id == ConditionId.IDLE.id
        runCatching {
            if (needGuard) {
                if (standbyGuardLock == null) {
                    val pm = getSystemService(PowerManager::class.java)
                    standbyGuardLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "PowerTrace:standby-guard",
                    ).apply { setReferenceCounted(false) }
                }
                if (standbyGuardLock?.isHeld != true) {
                    standbyGuardLock?.acquire(STANDBY_GUARD_TIMEOUT_MS)
                    LabLog.w(TAG, "standby guard acquired: IDLE sampling keeps CPU awake (documented bias)")
                }
            } else if (standbyGuardLock?.isHeld == true) {
                standbyGuardLock?.release()
                LabLog.i(TAG, "standby guard released")
            }
        }.onFailure { LabLog.e(TAG, "standby guard update failed", it) }
    }

    companion object {
        private const val TAG = "PowerTraceService"

        const val ACTION_START = "com.mobilelab.powertrace.action.START"
        const val ACTION_SAMPLE = "com.mobilelab.powertrace.action.SAMPLE"
        const val ACTION_PAUSE = "com.mobilelab.powertrace.action.PAUSE"
        const val ACTION_RESUME = "com.mobilelab.powertrace.action.RESUME"
        const val ACTION_SKIP = "com.mobilelab.powertrace.action.SKIP"
        const val ACTION_ABORT = "com.mobilelab.powertrace.action.ABORT"
        const val ACTION_STOP = "com.mobilelab.powertrace.action.STOP"
        const val ACTION_RAW_TICK = "com.mobilelab.powertrace.action.RAW_TICK"

        /** 待机守护锁上限 40 min（30 min 工况 + 缓冲），到期自动释放避免泄漏。 */
        const val STANDBY_GUARD_TIMEOUT_MS = 40L * 60L * 1000L

        fun intent(context: Context, action: String): Intent =
            Intent(context, SamplingService::class.java).setAction(action)
    }
}

/** UI 侧统一入口，避免每个界面重复写 startForegroundService 的异常处理。 */
object SamplingControl {

    fun start(context: Context) = dispatch(context, SamplingService.ACTION_START)
    fun resume(context: Context) = dispatch(context, SamplingService.ACTION_RESUME)
    fun sampleNow(context: Context) = dispatch(context, SamplingService.ACTION_SAMPLE)
    fun pause(context: Context) = dispatch(context, SamplingService.ACTION_PAUSE)
    fun skipCondition(context: Context) = dispatch(context, SamplingService.ACTION_SKIP)
    fun abort(context: Context) = dispatch(context, SamplingService.ACTION_ABORT)
    fun stop(context: Context) = dispatch(context, SamplingService.ACTION_STOP)

    private fun dispatch(context: Context, action: String) {
        runCatching {
            ContextCompat.startForegroundService(context, SamplingService.intent(context, action))
        }.onFailure {
            LabLog.e("PowerTraceControl", "cannot start sampling service for $action", it)
        }
    }
}
