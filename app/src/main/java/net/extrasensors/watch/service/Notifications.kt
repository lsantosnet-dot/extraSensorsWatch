package net.extrasensors.watch.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import net.extrasensors.watch.Permissions
import net.extrasensors.watch.R
import net.extrasensors.watch.complication.SunComplicationService
import net.extrasensors.watch.data.SunApplicationEntity
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.graph
import net.extrasensors.watch.ui.MainActivity
import java.time.ZoneId

object Notifications {
    const val CH_SERVICE = "medindo"
    const val CH_RAIN = "chuva"
    const val CH_SUN = "protetor"

    const val ID_SERVICE = 1
    const val ID_RAIN = 2
    const val ID_SUN = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Medindo sensores", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RAIN, "Alerta de chuva", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUN, "Protetor solar", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
    }

    fun openAppIntent(context: Context, page: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PAGE, page)
        return PendingIntent.getActivity(
            context, page, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Notificação discreta do serviço, ligada a uma Ongoing Activity (ícone no mostrador). */
    fun serviceNotification(context: Context, text: String, page: Int): Notification {
        val open = openAppIntent(context, page)
        val builder = NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sensores")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        OngoingActivity.Builder(context, ID_SERVICE, builder)
            .setStaticIcon(R.drawable.ic_stat)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(context)
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (!Permissions.notificationsGranted(context)) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun updateService(context: Context, text: String, page: Int) =
        post(context, ID_SERVICE, serviceNotification(context, text, page))

    fun rainAlert(context: Context) {
        val n = NotificationCompat.Builder(context, CH_RAIN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Queda rápida da pressão")
            .setContentText("Chuva provável nas próximas horas")
            .setContentIntent(openAppIntent(context, 2))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, ID_RAIN, n)
    }

    fun sunAlert(context: Context) {
        val applied = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SunscreenReceiver::class.java).setAction(SunscreenReceiver.ACTION_APPLIED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_SUN)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle("Hora de reaplicar protetor")
            .setContentText("Você atingiu a meta de exposição ao sol.")
            .setContentIntent(openAppIntent(context, 5))
            .addAction(R.drawable.ic_sun, "Apliquei protetor", applied)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, ID_SUN, n)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun requestComplicationUpdate(context: Context) {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, SunComplicationService::class.java))
            .requestUpdateAll()
    }
}

/** Ação "Apliquei protetor", usada pela tela Sol e pela notificação. */
object SunActions {
    suspend fun applySunscreen(context: Context) {
        val g = context.graph
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        g.state.updateSun({ SunExposure.initial(now, zone) }) { SunExposure.applySunscreen(it, now, zone) }
        g.db.sun().insertApplication(SunApplicationEntity(timeMillis = now))
        Notifications.cancel(context, Notifications.ID_SUN)
        Notifications.requestComplicationUpdate(context)
    }
}
