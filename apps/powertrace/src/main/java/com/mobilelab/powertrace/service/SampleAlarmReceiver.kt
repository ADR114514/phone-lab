package com.mobilelab.powertrace.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mobilelab.core.log.LabLog

/**
 * 精确闹钟到期广播：把采集服务叫醒。
 *
 * Android 12+ 的后台启动前台服务限制对"精确闹钟触发"有豁免，
 * 因此这里可以在应用进程被杀后重新拉起 [SamplingService] 并补采。
 */
class SampleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SAMPLE_ALARM) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                SamplingService.intent(context, SamplingService.ACTION_SAMPLE),
            )
        }.onFailure { LabLog.e(TAG, "exact alarm wake-up failed", it) }
    }

    companion object {
        private const val TAG = "PowerTraceAlarm"
        const val ACTION_SAMPLE_ALARM = "com.mobilelab.powertrace.action.SAMPLE_ALARM"
    }
}
