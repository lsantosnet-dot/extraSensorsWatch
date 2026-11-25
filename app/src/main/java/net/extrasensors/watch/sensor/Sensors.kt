package net.extrasensors.watch.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import net.extrasensors.watch.domain.median

data class SensorReading(val timeMillis: Long, val value: Float)

private fun SensorManager.events(
    sensor: Sensor,
    samplingPeriodUs: Int,
    maxReportLatencyUs: Int,
): Flow<SensorReading> = callbackFlow {
    // Converte o timestamp do evento (nanos desde o boot) para relógio de parede.
    val bootOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            trySend(SensorReading(bootOffsetMs + event.timestamp / 1_000_000, event.values[0]))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    if (!registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs)) {
        close(IllegalStateException("Falha ao registrar ${sensor.name}"))
    }
    awaitClose { unregisterListener(listener) }
}

interface PressureSource {
    val available: Boolean
    fun readings(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_NORMAL): Flow<SensorReading>
    /** Lê por [durationMs] e devolve a mediana em hPa, ou null se não houver leitura. */
    suspend fun sampleMedian(durationMs: Long = 10_000): Double?
}

interface LightSource {
    val available: Boolean
    /** Leituras em lux. [batchLatencyUs] > 0 ativa batching no hardware. */
    fun readings(batchLatencyUs: Int = 0): Flow<SensorReading>
}

interface OffBodySource {
    val available: Boolean
    /** true = no pulso. */
    fun onWrist(): Flow<Boolean>
}

interface StepCounterSource {
    val available: Boolean
    /** Total de passos desde o boot. */
    fun counts(): Flow<SensorReading>
}

class AndroidPressureSource(context: Context) : PressureSource {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    override val available get() = sensor != null

    override fun readings(samplingPeriodUs: Int): Flow<SensorReading> =
        sm.events(requireNotNull(sensor), samplingPeriodUs, 0)

    override suspend fun sampleMedian(durationMs: Long): Double? {
        if (sensor == null) return null
        val values = mutableListOf<Double>()
        withTimeoutOrNull(durationMs) {
            // 5 Hz explícito: alguns relógios ignoram SENSOR_DELAY_NORMAL com a tela apagada.
            readings(200_000).collect { values += it.value.toDouble() }
        }
        if (values.isEmpty()) {
            // Segunda tentativa, mais rápida e mais longa.
            withTimeoutOrNull(durationMs) {
                readings(SensorManager.SENSOR_DELAY_UI).collect { values += it.value.toDouble() }
            }
        }
        return if (values.isEmpty()) null else median(values.filter { it in 300.0..1100.0 }.ifEmpty { values })
    }
}

class AndroidLightSource(context: Context) : LightSource {
    private val sm = context.getSystemService(SensorManager::class.java)
    // Prefere a variante "wake-up", quando existe, para receber eventos com a CPU dormindo.
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_LIGHT, true) ?: sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    override val available get() = sensor != null

    override fun readings(batchLatencyUs: Int): Flow<SensorReading> =
        sm.events(requireNotNull(sensor), SensorManager.SENSOR_DELAY_NORMAL, batchLatencyUs)
}

class AndroidOffBodySource(context: Context) : OffBodySource {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    override val available get() = sensor != null

    override fun onWrist(): Flow<Boolean> =
        sm.events(requireNotNull(sensor), SensorManager.SENSOR_DELAY_NORMAL, 0).map { it.value != 0f }
}

class AndroidStepCounterSource(context: Context) : StepCounterSource {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    override val available get() = sensor != null

    override fun counts(): Flow<SensorReading> =
        sm.events(requireNotNull(sensor), SensorManager.SENSOR_DELAY_NORMAL, 0)
}
