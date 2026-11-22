package com.mobilelab.chargeaudit.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mobilelab.chargeaudit.MainActivity
import com.mobilelab.chargeaudit.data.AuditState
import org.json.JSONObject

/** 10 分钟提醒：AlarmManager 精确闹钟 + 通知；点通知回到 App 录入。 */
object Reminder {

    const val ACTION = "com.mobilelab.chargeaudit.REMINDER"
    private const val CHANNEL_ID = "chargeaudit_reminder"
    private const val REQUEST_CODE = 7001
    private const val NOTIFICATION_ID = 7001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "充电记录提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "每 10 分钟提醒记录充电宝输出电压/电流"
        }
        manager.createNotificationChannel(channel)
    }

    fun schedule(context: Context, atMs: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        runCatching {
            if (alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(pendingIntent(context)) }
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    fun postNow(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("该记录本点了")
            .setContentText("每 10 分钟记录一次充电宝输出电压/电流")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, REQUEST_CODE + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Reminder.ensureChannel(context)
        Reminder.postNow(context)
        if (isSessionActive(context)) {
            Reminder.schedule(context, System.currentTimeMillis() + AuditState.INTERVAL_MS)
        }
    }

    private fun isSessionActive(context: Context): Boolean = runCatching {
        val raw = context.getSharedPreferences(AuditState.PREFS, Context.MODE_PRIVATE)
            .getString(AuditState.KEY_STATE, null) ?: return false
        JSONObject(raw).optBoolean("active", false)
    }.getOrDefault(false)
}
