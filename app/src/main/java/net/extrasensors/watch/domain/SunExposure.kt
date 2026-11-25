package net.extrasensors.watch.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Estado persistido da exposição solar. Tempos em milissegundos. */
data class SunState(
    val epochDay: Long,
    val todayMs: Long = 0,
    val sinceApplicationMs: Long = 0,
    val lastTickAt: Long? = null,
    val goalNotified: Boolean = false,
    val lastApplicationAt: Long? = null,
)

data class SunConfig(
    val luxThreshold: Float = 10_000f,
    val goalMs: Long = 2 * 60 * 60 * 1000L,
    val maxGapMs: Long = 90_000,
)

data class ClosedDay(val epochDay: Long, val totalMs: Long)

data class SunTickResult(
    val state: SunState,
    val goalReached: Boolean,
    val closedDay: ClosedDay?,
    val counted: Boolean,
)

/**
 * Acumula o tempo sob luz forte. É chamado por um tick periódico (~30 s) com o último lux
 * recebido: o sensor de luz é "on-change" e pode ficar minutos sem evento sob sol constante,
 * então o silêncio do sensor não é tratado como falta de dado. Intervalos maiores que
 * [SunConfig.maxGapMs] indicam serviço parado ou CPU dormindo e são descartados.
 */
object SunExposure {

    fun dayOf(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun startOfDay(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    fun initial(now: Long, zone: ZoneId) = SunState(epochDay = dayOf(now, zone))

    /** Aplica a virada de dia sem contar tempo (para leitura na UI). */
    fun normalized(state: SunState, now: Long, zone: ZoneId): SunState {
        val today = dayOf(now, zone)
        return if (today == state.epochDay) state else newDay(state, today)
    }

    private fun newDay(state: SunState, day: Long) = state.copy(
        epochDay = day,
        todayMs = 0,
        sinceApplicationMs = 0,
        goalNotified = false,
    )

    fun onTick(
        state: SunState,
        now: Long,
        lux: Float?,
        onWrist: Boolean,
        config: SunConfig,
        zone: ZoneId,
    ): SunTickResult {
        val last = state.lastTickAt
        val dt = if (last == null) -1 else now - last
        val counting = dt in 1..config.maxGapMs && onWrist && lux != null && lux > config.luxThreshold

        var s = state
        var closed: ClosedDay? = null
        val today = dayOf(now, zone)

        if (today != s.epochDay) {
            var beforeMidnight = 0L
            if (counting && last != null) {
                val midnight = startOfDay(today, zone)
                beforeMidnight = (midnight - last).coerceIn(0, dt)
            }
            closed = ClosedDay(s.epochDay, s.todayMs + beforeMidnight)
            s = newDay(s, today)
            if (counting) {
                val after = dt - beforeMidnight
                s = s.copy(todayMs = after, sinceApplicationMs = after)
            }
        } else if (counting) {
            s = s.copy(todayMs = s.todayMs + dt, sinceApplicationMs = s.sinceApplicationMs + dt)
        }

        s = s.copy(lastTickAt = now)
        var goal = false
        if (!s.goalNotified && s.sinceApplicationMs >= config.goalMs) {
            goal = true
            s = s.copy(goalNotified = true)
        }
        return SunTickResult(s, goal, closed, counting)
    }

    fun applySunscreen(state: SunState, now: Long, zone: ZoneId): SunState =
        normalized(state, now, zone).copy(
            sinceApplicationMs = 0,
            goalNotified = false,
            lastApplicationAt = now,
        )
}
