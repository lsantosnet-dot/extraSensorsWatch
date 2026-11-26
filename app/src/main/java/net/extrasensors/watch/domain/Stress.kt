package net.extrasensors.watch.domain

import kotlin.math.abs
import kotlin.math.sqrt

data class HrvResult(val rmssdMs: Double, val meanHr: Double, val beats: Int)

/**
 * RMSSD a partir dos intervalos entre batimentos (IBI, ms) já marcados como válidos pelo sensor.
 * Remove artefatos antes do cálculo:
 * - intervalos fora de 300–2000 ms (200 a 30 bpm);
 * - intervalos que diferem mais de 20% do anterior aceito (batimento perdido ou extra).
 * Diferenças só são somadas entre intervalos aceitos consecutivos, para um artefato removido
 * não criar uma diferença falsa.
 */
object Hrv {
    const val MIN_BEATS = 30

    fun rmssd(ibis: List<Int>): HrvResult? {
        val accepted = mutableListOf<Int>()
        val diffs = mutableListOf<Double>()
        var prev: Int? = null
        for (ibi in ibis) {
            if (ibi !in 300..2000) { prev = null; continue }
            val p = prev
            if (p != null && abs(ibi - p) > 0.2 * p) { prev = null; continue }
            if (p != null) diffs += (ibi - p).toDouble()
            accepted += ibi
            prev = ibi
        }
        if (accepted.size < MIN_BEATS || diffs.size < MIN_BEATS - 1) return null
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        return HrvResult(rmssd, 60_000.0 / accepted.average(), accepted.size)
    }
}

data class EdaWindow(val timeMillis: Long, val mean: Double, val min: Double, val max: Double, val peaks: Int)

/**
 * Agrega a condutância da pele (1 Hz) em janelas de 10 s e conta picos de ativação
 * (respostas fásicas): subida de pelo menos [peakRise] µS a partir do vale mais recente.
 * Um pico só é contado de novo depois que o sinal recua [peakRise] a partir do topo.
 */
class EdaAggregator(
    private val windowMs: Long = 10_000,
    private val peakRise: Double = 0.05,
) {
    private var windowStart: Long? = null
    private val values = mutableListOf<Double>()
    private var peaksInWindow = 0
    private var trough: Double? = null
    private var top = 0.0
    private var inPeak = false

    /** Retorna a janela fechada quando [t] passa do fim da janela atual. */
    fun add(t: Long, us: Double): EdaWindow? {
        var closed: EdaWindow? = null
        val start = windowStart
        if (start != null && t - start >= windowMs) {
            closed = close()
        }
        if (windowStart == null) windowStart = t - t % windowMs
        values += us
        detectPeak(us)
        return closed
    }

    private fun detectPeak(v: Double) {
        val tr = trough
        if (tr == null) { trough = v; return }
        if (!inPeak) {
            if (v < tr) trough = v
            else if (v - tr >= peakRise) { inPeak = true; top = v; peaksInWindow++ }
        } else {
            if (v > top) top = v
            else if (top - v >= peakRise) { inPeak = false; trough = v }
        }
    }

    /** Fecha a janela em andamento (fim da sessão). */
    fun flush(): EdaWindow? = if (values.isEmpty()) null else close()

    private fun close(): EdaWindow {
        val w = EdaWindow(windowStart!!, values.average(), values.min(), values.max(), peaksInWindow)
        values.clear()
        peaksInWindow = 0
        windowStart = null
        return w
    }
}
