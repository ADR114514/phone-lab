package com.mobilelab.powertrace.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.permission.canScheduleExactAlarms
import com.mobilelab.powertrace.MainActivity

/**
 * 采样调度：Handler + AlarmManager.setExactAndAllowWhileIdle 双保险。
 *
 * - Handler：设备醒着时毫秒级准确（也会及时刷新 UI/通知）；
 * - AlarmManager：熄屏/Doze 时负责把设备唤醒，否则 30 min 待机工况会漏点；
 * - 没有精确闹钟权限时退化为 setAndAllowWhileIdle（不精确），UI 会提示误差风险。
 *
 * 两路都只是"叫醒服务"，真正的到期判断在 [com.mobilelab.powertrace.data.CampaignEngine]，
 * 因此重复触发不会重复写数据。
 */
class SamplingScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    private val alarmIntent: PendingIntent by lazy {
        val intent = Intent(context, SampleAlarmReceiver::class.java)
            .setAction(SampleAlarmReceiver.ACTION_SAMPLE_ALARM)
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    val exactAlarmAvailable: Boolean
        get() = runCatching { canScheduleExactAlarms(context) }.getOrDefault(false)

    /** [wallClockAtMs] 为 Unix epoch ms；内部换算成 elapsedRealtime 避免墙钟跳变。 */
    fun schedule(wallClockAtMs: Long) {
        cancel()
        val delay = (wallClockAtMs - System.currentTimeMillis()).coerceIn(0L, MAX_DELAY_MS)
        val triggerElapsed = SystemClock.elapsedRealtime() + delay
        runCatching {
            if (exactAlarmAvailable) {
                // 国产 ROM（实测 OnePlus/ColorOS Android 16）会把 setExactAndAllowWhileIdle
                // 在 App Standby/省电策略下批量延后（dumpsys alarm 可见 +2 天 policy adjustment）。
                // setAlarmClock 属于"用户可见闹钟"通道，系统与 OEM 都更少节流，因此优先使用；
                // 代价是状态栏会出现闹钟图标（SOP 中已说明，实验期间正常现象）。
                val showIntent = PendingIntent.getActivity(
                    context,
                    SHOW_REQUEST_CODE,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(wallClockAtMs, showIntent),
                    alarmIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, alarmIntent)
            }
        }.onFailure {
            // setAlarmClock 失败时退回普通精确闹钟，保证有唤醒来源。
            LabLog.e(TAG, "setAlarmClock failed exact=$exactAlarmAvailable, fallback to setExact", it)
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsed,
                    alarmIntent,
                )
            }.onFailure { LabLog.e(TAG, "fallback exact alarm also failed", it) }
        }

        val runnable = Runnable {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    SamplingService.intent(context, SamplingService.ACTION_SAMPLE),
                )
            }.onFailure { LabLog.e(TAG, "start service from handler failed", it) }
        }
        tick = runnable
        handler.postDelayed(runnable, delay)
    }

    fun cancel() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        runCatching { alarmManager.cancel(alarmIntent) }
    }

    private companion object {
        const val TAG = "PowerTraceSched"
        const val REQUEST_CODE = 4101
        const val SHOW_REQUEST_CODE = 4102
        const val MAX_DELAY_MS = 31L * 60L * 1000L
    }
}
