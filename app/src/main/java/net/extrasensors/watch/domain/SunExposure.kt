package net.extrasensors.watch.domain

import net.extrasensors.shared.SkinType
import net.extrasensors.shared.UvMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Estado persistido da exposição solar. Tempos em milissegundos, doses em SED. */
data class SunState(
    val epochDay: Long,
    val todayMs: Long = 0,
    val sinceApplicationMs: Long = 0,
    val lastTickAt: Long? = null,
    val goalNotified: Boolean = false,
    val lastApplicationAt: Long? = null,
    val doseTodaySed: Double = 0.0,
    val doseAlertDay: Long? = null,
    val lastUvAlertAt: Long? = null,
)

data class SunConfig(
    val luxThreshold: Float = 10_000f,
    val goalMs: Long = 2 * 60 * 60 * 1000L,
    val maxGapMs: Long = 90_000,
    val skinType: SkinType = SkinType.II,
    /** Fração da MED que dispara o alerta "Aplique protetor". */
    val doseAlertFraction: Double = 0.5,
    val uvAlertIntervalMs: Long = 2 * 60 * 60 * 1000L,
)

data class ClosedDay(val epochDay: Long, val totalMs: Long)

data class SunTickResult(
    val state: SunState,
    /** 2 h de exposição com UV ≥ 3 (ou UV desconhecido) desde o protetor. */
    val goalReached: Boolean,
    val closedDay: ClosedDay?,
    val counted: Boolean,
    /** Tempo exposto contado neste tick (para os blocos de 5 min). */
    val exposedMs: Long = 0,
    /** Dose recebida neste tick. */
    val doseSed: Double = 0.0,
    /** Sem protetor hoje e dose do dia ≥ fração da MED. */
    val doseAlert: Boolean = false,
    /** Exposto com UV ≥ 8. */
    val uvAlert: Boolean = false,
)

/**
 * Acumula o tempo sob luz forte e a dose de UV. É chamado por um tick periódico (~30 s) com o
 * último lux recebido: o sensor de luz é "on-change" e pode ficar minutos sem evento sob sol
 * constante, então o silêncio do sensor não é tratado como falta de dado. Intervalos maiores que
 * [SunConfig.maxGapMs] indicam serviço parado ou CPU dormindo e são descartados.
 *
 * O índice UV vem da previsão do celular. O relógio mede luz visível, não UV: a luz diz QUANDO
 * você está no sol; o índice UV diz QUANTO o sol queima naquela hora.
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
        doseTodaySed = 0.0,
    )

    fun appliedToday(state: SunState, now: Long, zone: ZoneId): Boolean =
        state.lastApplicationAt?.let { dayOf(it, zone) == dayOf(now, zone) } ?: false

    /** O tempo conta para "reaplicar" só com UV ≥ 3 (OMS) ou quando o UV é desconhecido. */
    private fun countsForReapply(uvi: Double?) = uvi == null || uvi >= UvMath.PROTECTION_UVI

    /** Abaixo disso é sombra clara ou céu encoberto (10 a 25 mil lux). */
    const val SHADE_LUX = 25_000f
    /** A partir disso é sol direto (50 mil a mais de 100 mil lux). */
    const val DIRECT_SUN_LUX = 50_000f

    /**
     * Fração do índice UV previsto que chega ao pulso, pela faixa de luz. Regra prática para não
     * somar o UV cheio na sombra: 0,3 abaixo de 25 mil lux, 0,6 até 50 mil e 1,0 a partir daí.
     */
    fun uvFraction(lux: Float): Double = when {
        lux < SHADE_LUX -> 0.3
        lux < DIRECT_SUN_LUX -> 0.6
        else -> 1.0
    }

    fun onTick(
        state: SunState,
        now: Long,
        lux: Float?,
        onWrist: Boolean,
        config: SunConfig,
        zone: ZoneId,
        uvi: Double? = null,
    ): SunTickResult {
        val last = state.lastTickAt
        val dt = if (last == null) -1 else now - last
        val counting = dt in 1..config.maxGapMs && onWrist && lux != null && lux > config.luxThreshold
        val reapply = countsForReapply(uvi)
        val dose = if (counting && uvi != null && lux != null) UvMath.doseSed(uvi, dt) * uvFraction(lux) else 0.0

        fun SunState.add(ms: Long, d: Double) = copy(
            todayMs = todayMs + ms,
            sinceApplicationMs = sinceApplicationMs + if (reapply) ms else 0,
            doseTodaySed = doseTodaySed + d,
        )

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
                s = s.add(after, dose * after / dt)
            }
        } else if (counting) {
            s = s.add(dt, dose)
        }

        s = s.copy(lastTickAt = now)

        var goal = false
        if (!s.goalNotified && s.sinceApplicationMs >= config.goalMs) {
            goal = true
            s = s.copy(goalNotified = true)
        }

        var doseAlert = false
        val limit = config.skinType.medSed * config.doseAlertFraction
        if (!appliedToday(s, now, zone) && s.doseTodaySed >= limit && s.doseAlertDay != today) {
            doseAlert = true
            s = s.copy(doseAlertDay = today)
        }

        var uvAlert = false
        if (counting && uvi != null && uvi >= UvMath.VERY_HIGH_UVI &&
            (s.lastUvAlertAt == null || now - s.lastUvAlertAt!! >= config.uvAlertIntervalMs)
        ) {
            uvAlert = true
            s = s.copy(lastUvAlertAt = now)
        }

        return SunTickResult(
            state = s,
            goalReached = goal,
            closedDay = closed,
            counted = counting,
            exposedMs = if (counting) dt else 0,
            doseSed = dose,
            doseAlert = doseAlert,
            uvAlert = uvAlert,
        )
    }

    fun applySunscreen(state: SunState, now: Long, zone: ZoneId): SunState =
        normalized(state, now, zone).copy(
            sinceApplicationMs = 0,
            goalNotified = false,
            lastApplicationAt = now,
        )
}
