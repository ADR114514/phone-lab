package com.mobilelab.powertrace.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.powertrace.MainActivity
import com.mobilelab.powertrace.R
import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.SamplingConfig

/**
 * 前台服务常驻通知（工况 + 进度 + 倒计时）与完成提醒。
 *
 * 通知权限被拒绝时前台服务仍可运行（系统会静默显示最低限度通知），
 * 这里全部调用都做 runCatching，不会因通知失败中断采集。
 */
object AppNotifications {

    const val CHANNEL_SERVICE = "powertrace_service"
    const val CHANNEL_EVENTS = "powertrace_events"
    const val ID_SAMPLING = 4101
    const val ID_EVENTS = 4102

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "采集常驻通知", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "采集期间显示当前工况与采样进度"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "批次事件", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "批次完成、中止等提醒"
                },
            )
        }
    }

    /** 前台服务通知：当前工况 / 第 k 个采样点 / 距下次采样倒计时 + 进度条。 */
    fun samplingNotification(context: Context, state: CampaignState?): Notification {
        val now = System.currentTimeMillis()
        val current = state?.current
        val block = current?.block ?: '-'
        val conditionLabel = current?.condition?.label ?: "等待开始"
        val samplesDone = current?.samplesDone ?: 0
        val nextIndex = (samplesDone + 1).coerceAtMost(SamplingConfig.SAMPLES_PER_CONDITION)
        val remaining = state?.let { CampaignEngine.remainingMs(it, now) } ?: 0L
        val elapsed = current?.elapsedMs(now) ?: 0L
        val progress = ((elapsed * 100) / SamplingConfig.CONDITION_DURATION_MS).toInt().coerceIn(0, 100)

        val title = "功耗热测 · $block $conditionLabel"
        val text = if (current == null) {
            "等待开始采集"
        } else {
            "第 $nextIndex/${SamplingConfig.SAMPLES_PER_CONDITION} 个采样点 · 距下次 ${TimeFmt.elapsedMinSec(remaining)}"
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(state?.campaignId ?: "PowerTrace")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, progress, false)
            .setContentIntent(mainActivityIntent(context))
            .addAction(0, "暂停", serviceAction(context, SamplingService.ACTION_PAUSE, 21))
            .addAction(0, "继续", serviceAction(context, SamplingService.ACTION_RESUME, 22))
            .build()
    }

    /** 批次完成提醒（非前台通知）。 */
    fun notifyCampaignFinished(context: Context, state: CampaignState) {
        // POST_NOTIFICATIONS 是 Android 13+ 运行时权限：显式复核后再调用，拒绝时静默跳过。
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LabLog.w("PowerTraceNotify", "POST_NOTIFICATIONS denied, skip finished notification")
            return
        }
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("批次完成：${state.campaignId}")
                .setContentText("已完成 ${state.conditions.count { it.completed }}/${state.conditions.size} 个工况，可在结果页导出")
                .setAutoCancel(true)
                .setContentIntent(mainActivityIntent(context))
                .build()
            NotificationManagerCompat.from(context).notify(ID_EVENTS, notification)
        }.onFailure { LabLog.w("PowerTraceNotify", "notify finished failed: ${it.message}") }
    }

    fun notifyCampaignAborted(context: Context, state: CampaignState) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LabLog.w("PowerTraceNotify", "POST_NOTIFICATIONS denied, skip aborted notification")
            return
        }
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("批次已放弃：${state.campaignId}")
                .setContentText("已采数据保留，可在结果页查看或导出")
                .setAutoCancel(true)
                .setContentIntent(mainActivityIntent(context))
                .build()
            NotificationManagerCompat.from(context).notify(ID_EVENTS, notification)
        }.onFailure { LabLog.w("PowerTraceNotify", "notify aborted failed: ${it.message}") }
    }

    fun updateSamplingNotification(context: Context, state: CampaignState?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_SAMPLING, samplingNotification(context, state))
        }.onFailure { LabLog.w("PowerTraceNotify", "update notification failed: ${it.message}") }
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SamplingService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
