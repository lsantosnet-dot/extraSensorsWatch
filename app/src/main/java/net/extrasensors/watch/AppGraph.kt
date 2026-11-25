package net.extrasensors.watch

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import net.extrasensors.watch.data.AppDatabase
import net.extrasensors.watch.data.SettingsRepository
import net.extrasensors.watch.data.StateRepository
import net.extrasensors.watch.sensor.AndroidLightSource
import net.extrasensors.watch.sensor.AndroidOffBodySource
import net.extrasensors.watch.sensor.AndroidPressureSource
import net.extrasensors.watch.sensor.AndroidSoundSource
import net.extrasensors.watch.sensor.AndroidStepCounterSource
import net.extrasensors.watch.sensor.SamsungHealthManager
import net.extrasensors.watch.sensor.SamsungSkinTemperatureSource
import net.extrasensors.watch.sensor.SamsungSweatLossSource

/** Fase da medição de perda de suor, publicada pelo serviço. */
sealed interface SweatPhase {
    data object Idle : SweatPhase
    data object Starting : SweatPhase
    data class Running(val startedAt: Long) : SweatPhase
    data object Calculating : SweatPhase
    data class Done(val liters: Double) : SweatPhase
    data class Error(val message: String) : SweatPhase
}

data class ClimbLive(
    val sessionId: Long,
    val startedAt: Long,
    val gain: Double,
    val loss: Double,
    val altitude: Double?,
)

/** Estado vivo compartilhado entre o serviço e a interface (mesmo processo). */
class LiveState {
    val serviceRunning = MutableStateFlow(false)
    val onWrist = MutableStateFlow<Boolean?>(null)
    val lastLux = MutableStateFlow<Float?>(null)
    val climb = MutableStateFlow<ClimbLive?>(null)
    val sweat = MutableStateFlow<SweatPhase>(SweatPhase.Idle)
    val skinError = MutableStateFlow<String?>(null)
}

class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val settings = SettingsRepository(context)
    val state = StateRepository(context)
    val live = LiveState()

    val pressure = AndroidPressureSource(context)
    val light = AndroidLightSource(context)
    val offBody = AndroidOffBodySource(context)
    val steps = AndroidStepCounterSource(context)
    val sound = AndroidSoundSource(context)

    val health = SamsungHealthManager(context)
    val skin = SamsungSkinTemperatureSource(health)
    val sweat = SamsungSweatLossSource(health)
}

class ExtraSensorsApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        net.extrasensors.watch.service.Notifications.createChannels(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as ExtraSensorsApp).graph

object Permissions {
    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Só considera permissões que existem neste sistema (evita pedir uma que nunca será concedida). */
    private fun defined(context: Context, permission: String): Boolean =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess

    /** Permissões obrigatórias para o Samsung Health Sensor SDK neste Android. */
    fun healthRequired(context: Context): List<String> {
        val base = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
        else Manifest.permission.BODY_SENSORS
        return listOf(base).filter { defined(context, it) }
    }

    /** Permissões adicionais pedidas junto, mas que não bloqueiam a tela se negadas. */
    fun healthOptional(context: Context): List<String> = listOf(
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
        "android.permission.health.READ_SKIN_TEMPERATURE",
    ).filter { defined(context, it) }

    fun notificationsGranted(context: Context): Boolean = granted(context, Manifest.permission.POST_NOTIFICATIONS)
}
