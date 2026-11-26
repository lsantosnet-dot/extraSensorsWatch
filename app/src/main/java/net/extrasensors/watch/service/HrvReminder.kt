package net.extrasensors.watch.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import net.extrasensors.watch.R
import net.extrasensors.watch.data.AppSettings
import net.extrasensors.watch.graph
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Lembrete diário para medir a HRV no mesmo horário (padrão 7h). */
object HrvReminder {
    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 7, Intent(context, HrvReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun nextTrigger(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val at = now.toLocalDate().atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(now.zone)
        return if (at.isAfter(now)) at else LocalDate.from(now).plusDays(1).atTime(at.toLocalTime()).atZone(now.zone)
    }

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM; canScheduleExactAlarms() checado.
    fun schedule(context: Context, s: AppSettings) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(intent(context))
        if (!s.hrvReminder) return
        val at = nextTrigger(ZonedDateTime.now(ZoneId.systemDefault()), s.hrvReminderMin).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
    }
}

class HrvReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.graph.appScope.launch {
            try {
                val s = context.graph.settings.current()
                if (s.hrvReminder) {
                    val n = NotificationCompat.Builder(context, Notifications.CH_HRV)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("Medir a recuperação")
                        .setContentText("1 min parado, sentado ou deitado, para a HRV de hoje.")
                        .setContentIntent(Notifications.openAppIntent(context, Notifications.PAGE_HRV))
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .build()
                    Notifications.postPublic(context, Notifications.ID_HRV, n)
                }
                HrvReminder.schedule(context, s) // próximo dia
            } finally {
                pending.finish()
            }
        }
    }
}
