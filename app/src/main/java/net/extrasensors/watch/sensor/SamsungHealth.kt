package net.extrasensors.watch.sensor

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.DataType
import com.samsung.android.service.health.tracking.data.ExerciseState
import com.samsung.android.service.health.tracking.data.ExerciseType
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.extrasensors.watch.data.UserProfile

/** Estado da conexão com o serviço de saúde da Samsung (Health Platform). */
sealed interface HealthConnection {
    data object Idle : HealthConnection
    data object Connecting : HealthConnection
    data class Connected(val supported: Set<HealthTrackerType>) : HealthConnection
    data class Failed(val message: String) : HealthConnection
}

private fun trackerErrorMessage(e: HealthTracker.TrackerError): String = when (e) {
    HealthTracker.TrackerError.PERMISSION_ERROR -> "Permissão de sensores de saúde negada."
    HealthTracker.TrackerError.SDK_POLICY_ERROR ->
        "Ative o modo desenvolvedor do Health Platform no relógio (ver README)."
}

/** Mantém uma única conexão com o HealthTrackingService, compartilhada pelo app. */
class SamsungHealthManager(private val context: Context) {
    private val mutex = Mutex()
    private var service: HealthTrackingService? = null
    private val _state = MutableStateFlow<HealthConnection>(HealthConnection.Idle)
    val state: StateFlow<HealthConnection> = _state.asStateFlow()

    /** Conecta (se necessário) e devolve o estado resultante. */
    suspend fun connect(): HealthConnection = mutex.withLock {
        (state.value as? HealthConnection.Connected)?.let { return it }
        _state.value = HealthConnection.Connecting
        val result = CompletableDeferred<HealthConnection>()
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val types = runCatching {
                    service?.trackingCapability?.supportHealthTrackerTypes?.toSet().orEmpty()
                }.getOrDefault(emptySet())
                val c = HealthConnection.Connected(types)
                _state.value = c
                result.complete(c)
            }

            override fun onConnectionEnded() {
                _state.value = HealthConnection.Idle
                service = null
                result.complete(HealthConnection.Idle)
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                val msg = when (e.errorCode) {
                    HealthTrackerException.PACKAGE_NOT_INSTALLED ->
                        "Serviço de saúde da Samsung não instalado neste relógio."
                    HealthTrackerException.OLD_PLATFORM_VERSION ->
                        "Atualize o Health Platform pela Galaxy Store."
                    else -> "Falha ao conectar ao serviço de saúde: ${e.message ?: e.errorCode}"
                }
                val f = HealthConnection.Failed(msg)
                _state.value = f
                service = null
                result.complete(f)
            }
        }
        try {
            val s = HealthTrackingService(listener, context.applicationContext)
            service = s
            s.connectService()
        } catch (t: Throwable) {
            val f = HealthConnection.Failed("Serviço de saúde indisponível: ${t.message}")
            _state.value = f
            return f
        }
        withTimeoutOrNull(15_000) { result.await() }
            ?: HealthConnection.Failed("O serviço de saúde não respondeu.").also { _state.value = it }
    }

    fun tracker(
        type: HealthTrackerType,
        profile: TrackerUserProfile? = null,
        exercise: ExerciseType? = null,
    ): HealthTracker {
        val s = requireNotNull(service) { "Serviço de saúde não conectado" }
        return when {
            profile != null && exercise != null -> s.getHealthTracker(type, profile, exercise)
            profile != null -> s.getHealthTracker(type, profile)
            else -> s.getHealthTracker(type)
        }
    }
}

// ---------------- Temperatura da pele ----------------

enum class SkinMode { CONTINUOUS, ON_DEMAND, NONE }

sealed interface SkinMeasurement {
    data class Success(val timeMillis: Long, val celsius: Double) : SkinMeasurement
    data class Failure(val message: String) : SkinMeasurement
}

interface SkinTemperatureSource {
    /** Descobre se o relógio oferece leitura contínua, sob demanda ou nenhuma. */
    suspend fun mode(): Result<SkinMode>
    /** Uma medição sob demanda. */
    suspend fun measureOnce(timeoutMs: Long = 30_000): SkinMeasurement
    /** Leituras contínuas enquanto coletado. */
    fun continuous(): Flow<SkinMeasurement>
}

class SamsungSkinTemperatureSource(private val health: SamsungHealthManager) : SkinTemperatureSource {

    private suspend fun connected(): HealthConnection.Connected? =
        health.connect() as? HealthConnection.Connected

    override suspend fun mode(): Result<SkinMode> {
        return when (val c = health.connect()) {
            is HealthConnection.Connected -> Result.success(
                when {
                    HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in c.supported -> SkinMode.CONTINUOUS
                    HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND in c.supported -> SkinMode.ON_DEMAND
                    else -> SkinMode.NONE
                }
            )
            is HealthConnection.Failed -> Result.failure(IllegalStateException(c.message))
            else -> Result.failure(IllegalStateException("Serviço de saúde desconectado."))
        }
    }

    private fun parse(dp: DataPoint): SkinMeasurement {
        val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS) ?: -1
        val temp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
        return if (status == 0 && temp != null && temp.isFinite() && temp > 0f) {
            val t = dp.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            SkinMeasurement.Success(t, temp.toDouble())
        } else {
            SkinMeasurement.Failure(
                "Medição inválida (código $status). Ajuste o relógio no pulso e fique parado."
            )
        }
    }

    override suspend fun measureOnce(timeoutMs: Long): SkinMeasurement {
        val c = connected() ?: return SkinMeasurement.Failure(
            (health.state.value as? HealthConnection.Failed)?.message ?: "Serviço de saúde desconectado."
        )
        val type = if (HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND in c.supported)
            HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND else HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
        if (type !in c.supported) return SkinMeasurement.Failure("Este relógio não mede temperatura da pele.")
        val tracker = health.tracker(type)
        val done = CompletableDeferred<SkinMeasurement>()
        tracker.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(list: MutableList<DataPoint>) {
                val m = list.map(::parse)
                (m.lastOrNull { it is SkinMeasurement.Success } ?: m.lastOrNull())?.let { done.complete(it) }
            }

            override fun onFlushCompleted() = Unit
            override fun onError(e: HealthTracker.TrackerError) {
                done.complete(SkinMeasurement.Failure(trackerErrorMessage(e)))
            }
        })
        return try {
            withTimeoutOrNull(timeoutMs) { done.await() }
                ?: SkinMeasurement.Failure("Sem resposta do sensor. Ajuste o relógio no pulso e tente de novo.")
        } finally {
            runCatching { tracker.unsetEventListener() }
        }
    }

    override fun continuous(): Flow<SkinMeasurement> = callbackFlow {
        val c = connected()
        if (c == null || HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS !in c.supported) {
            close()
            return@callbackFlow
        }
        val tracker = health.tracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        tracker.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(list: MutableList<DataPoint>) {
                list.forEach { trySend(parse(it)) }
            }

            override fun onFlushCompleted() = Unit
            override fun onError(e: HealthTracker.TrackerError) {
                trySend(SkinMeasurement.Failure(trackerErrorMessage(e)))
                close()
            }
        })
        awaitClose { runCatching { tracker.unsetEventListener() } }
    }
}

// ---------------- Perda de suor ----------------

sealed interface SweatOutcome {
    data class Success(val liters: Double) : SweatOutcome
    data class Failure(val message: String) : SweatOutcome
}

interface SweatSession {
    /** Envia a cadência atual (passos por minuto) ao tracker. */
    fun feedStepsPerMinute(spm: Float, timeMillis: Long)
    /** Encerra a corrida e aguarda o resultado. */
    suspend fun finish(timeoutMs: Long = 60_000): SweatOutcome
    fun cancel()
}

interface SweatLossSource {
    suspend fun isSupported(): Result<Boolean>
    suspend fun start(profile: UserProfile): Result<SweatSession>
}

class SamsungSweatLossSource(private val health: SamsungHealthManager) : SweatLossSource {

    override suspend fun isSupported(): Result<Boolean> = when (val c = health.connect()) {
        is HealthConnection.Connected -> Result.success(HealthTrackerType.SWEAT_LOSS in c.supported)
        is HealthConnection.Failed -> Result.failure(IllegalStateException(c.message))
        else -> Result.failure(IllegalStateException("Serviço de saúde desconectado."))
    }

    override suspend fun start(profile: UserProfile): Result<SweatSession> {
        val c = health.connect()
        if (c !is HealthConnection.Connected) {
            return Result.failure(
                IllegalStateException((c as? HealthConnection.Failed)?.message ?: "Serviço de saúde desconectado.")
            )
        }
        if (HealthTrackerType.SWEAT_LOSS !in c.supported) {
            return Result.failure(IllegalStateException("Este relógio não mede perda de suor."))
        }
        val p = TrackerUserProfile.Builder()
            .setHeight(profile.heightCm)
            .setWeight(profile.weightKg)
            .setGender(profile.gender)
            .setAge(profile.age)
            .build()
        return runCatching {
            val tracker = health.tracker(HealthTrackerType.SWEAT_LOSS, p, ExerciseType.RUNNING)
            SamsungSweatSession(tracker).also { it.begin() }
        }
    }
}

private class SamsungSweatSession(private val tracker: HealthTracker) : SweatSession {
    private val result = CompletableDeferred<SweatOutcome>()
    @Volatile private var stopped = false

    fun begin() {
        tracker.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(list: MutableList<DataPoint>) {
                if (!stopped) return
                val dp = list.lastOrNull() ?: return
                val status = dp.getValue(ValueKey.SweatLossSet.STATUS) ?: -1
                val ml = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS)
                if (status == 0 && ml != null && ml.isFinite() && ml >= 0f) {
                    // O SDK informa a perda em mililitros.
                    result.complete(SweatOutcome.Success(ml / 1000.0))
                } else {
                    result.complete(
                        SweatOutcome.Failure("O relógio não conseguiu estimar a perda de suor (código $status).")
                    )
                }
            }

            override fun onFlushCompleted() = Unit
            override fun onError(e: HealthTracker.TrackerError) {
                result.complete(SweatOutcome.Failure(trackerErrorMessage(e)))
            }
        })
        tracker.setExerciseState(ExerciseState.START)
    }

    override fun feedStepsPerMinute(spm: Float, timeMillis: Long) {
        if (stopped) return
        runCatching { tracker.setExerciseData(DataType.STEP_PER_MINUTE, floatArrayOf(spm), longArrayOf(timeMillis)) }
    }

    override suspend fun finish(timeoutMs: Long): SweatOutcome {
        stopped = true
        tracker.setExerciseState(ExerciseState.STOP)
        runCatching { tracker.flush() }
        val r = withTimeoutOrNull(timeoutMs) { result.await() }
            ?: SweatOutcome.Failure("O relógio não devolveu o resultado a tempo.")
        runCatching { tracker.unsetEventListener() }
        return r
    }

    override fun cancel() {
        stopped = true
        runCatching { tracker.setExerciseState(ExerciseState.STOP) }
        runCatching { tracker.unsetEventListener() }
    }
}
