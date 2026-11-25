package net.extrasensors.watch.domain

import kotlin.math.abs

data class PressurePoint(val timeMillis: Long, val hPa: Double)

enum class TrendState(val label: String, val shortLabel: String, val message: String, val color: Long) {
    RAPID_FALL("Queda rápida", "Queda rápida", "Chuva provável nas próximas horas", 0xFF4DD0E1),
    FALLING("Caindo", "Caindo", "O tempo pode piorar", 0xFF80DEEA),
    STABLE("Estável", "Estável", "Sem mudança de tempo à vista", 0xFFA5B4C3),
    RISING("Subindo", "Subindo", "O tempo tende a melhorar", 0xFF86EFAC),
}

/** Limiares da tendência. [band] vale para "Caindo" (−band) e "Subindo" (+band). */
data class TrendThresholds(val rapidFall: Double = -4.0, val band: Double = 1.5) {
    /** Depois de um alerta, só rearma quando ΔP passar deste valor. */
    val rearmAbove: Double get() = rapidFall + 1.0
}

sealed interface TrendResult {
    data class Available(val delta: Double, val state: TrendState) : TrendResult
    /** Ainda não há 3 h de histórico; [remainingMillis] é quanto falta. */
    data class Unavailable(val remainingMillis: Long) : TrendResult
}

object RainTrend {
    const val WINDOW_MS = 3 * 60 * 60 * 1000L
    const val TOLERANCE_MS = 10 * 60 * 1000L
    const val MIN_ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun classify(delta: Double, t: TrendThresholds): TrendState = when {
        delta <= t.rapidFall -> TrendState.RAPID_FALL
        delta <= -t.band -> TrendState.FALLING
        delta < t.band -> TrendState.STABLE
        else -> TrendState.RISING
    }

    /**
     * ΔP = P(agora) − P(3 h atrás). "Agora" é a amostra mais recente (até 10 min de idade);
     * "3 h atrás" é a amostra mais próxima de agora − 3 h, dentro de ±10 min.
     */
    fun compute(samples: List<PressurePoint>, now: Long, t: TrendThresholds): TrendResult {
        val sorted = samples.sortedBy { it.timeMillis }
        val latest = sorted.lastOrNull { it.timeMillis <= now }
        val oldest = sorted.firstOrNull()
        val missing = if (oldest == null) WINDOW_MS else (oldest.timeMillis + WINDOW_MS - now).coerceAtLeast(0)
        if (latest == null || now - latest.timeMillis > TOLERANCE_MS) {
            return TrendResult.Unavailable(if (latest == null) WINDOW_MS else missing)
        }
        val target = latest.timeMillis - WINDOW_MS
        val past = sorted
            .filter { abs(it.timeMillis - target) <= TOLERANCE_MS }
            .minByOrNull { abs(it.timeMillis - target) }
            ?: return TrendResult.Unavailable(if (missing == 0L) TOLERANCE_MS else missing)
        val delta = latest.hPa - past.hPa
        return TrendResult.Available(delta, classify(delta, t))
    }
}

data class RainAlertState(val armed: Boolean = true, val lastAlertAt: Long? = null)

object RainAlert {
    /** Retorna o novo estado e se a notificação deve ser disparada agora. */
    fun evaluate(
        result: TrendResult,
        state: RainAlertState,
        now: Long,
        t: TrendThresholds,
    ): Pair<RainAlertState, Boolean> {
        if (result !is TrendResult.Available) return state to false
        var s = state
        if (!s.armed && result.delta > t.rearmAbove) s = s.copy(armed = true)
        val intervalOk = s.lastAlertAt == null || now - s.lastAlertAt >= RainTrend.MIN_ALERT_INTERVAL_MS
        return if (s.armed && result.state == TrendState.RAPID_FALL && intervalOk) {
            RainAlertState(armed = false, lastAlertAt = now) to true
        } else {
            s to false
        }
    }
}
