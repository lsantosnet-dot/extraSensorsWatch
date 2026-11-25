package net.extrasensors.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.extrasensors.watch.graph

/** Religa o serviço após reiniciar o relógio ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.graph.appScope.launch {
            try {
                ServiceController.startIfEnabled(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Ação da notificação "Apliquei protetor". */
class SunscreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPLIED) return
        val pending = goAsync()
        context.graph.appScope.launch {
            try {
                SunActions.applySunscreen(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPLIED = "net.extrasensors.watch.SUNSCREEN_APPLIED"
    }
}

/** Alarme exato de 5 min: acorda a CPU para a amostra de pressão e o tick do Sol. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = SensorService.instance
        if (service != null) {
            service.onAlarm()
        } else {
            val pending = goAsync()
            context.graph.appScope.launch {
                try {
                    ServiceController.startIfEnabled(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
