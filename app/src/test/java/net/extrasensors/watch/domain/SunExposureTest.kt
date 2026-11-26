package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SunExposureTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val cfg = SunConfig(luxThreshold = 10_000f, goalMs = 2 * 3600_000L)
    private val start = LocalDateTime.of(2026, 1, 10, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val tick = 30_000L

    private fun ticks(
        from: SunState, t0: Long, count: Int, lux: Float?, onWrist: Boolean = true, step: Long = tick,
    ): Pair<SunState, List<SunTickResult>> {
        var s = from
        val results = mutableListOf<SunTickResult>()
        for (i in 1..count) {
            val r = SunExposure.onTick(s, t0 + i * step, lux, onWrist, cfg, zone)
            results += r
            s = r.state
        }
        return s to results
    }

    private fun begin(): SunState =
        SunExposure.onTick(SunExposure.initial(start, zone), start, 50_000f, true, cfg, zone).state

    @Test
    fun `sol continuo sem eventos novos do sensor conta pelo ultimo lux`() {
        // O mesmo lux repetido em cada tick = sensor "on-change" em silêncio.
        val (s, _) = ticks(begin(), start, 120, 50_000f)
        assertEquals(60 * 60_000L, s.sinceApplicationMs)
        assertEquals(60 * 60_000L, s.todayMs)
    }

    @Test
    fun `luz abaixo do limite nao conta`() {
        val (s, _) = ticks(begin(), start, 60, 8_000f)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `lacuna de 10 min e descartada`() {
        val s1 = ticks(begin(), start, 10, 50_000f).first // 5 min
        val afterGap = SunExposure.onTick(s1, start + 10 * tick + 10 * 60_000, 50_000f, true, cfg, zone)
        assertFalse(afterGap.counted)
        assertEquals(5 * 60_000L, afterGap.state.todayMs)
        // O próximo tick normal volta a contar.
        val next = SunExposure.onTick(afterGap.state, start + 10 * tick + 10 * 60_000 + tick, 50_000f, true, cfg, zone)
        assertEquals(5 * 60_000L + tick, next.state.todayMs)
    }

    @Test
    fun `fora do pulso nao conta`() {
        val (s, _) = ticks(begin(), start, 20, 80_000f, onWrist = false)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `sem leitura de lux nao conta`() {
        val (s, _) = ticks(begin(), start, 20, null)
        assertEquals(0L, s.todayMs)
    }

    @Test
    fun `meta atingida dispara uma unica vez`() {
        val (_, results) = ticks(begin(), start, 300, 50_000f) // 2,5 h
        assertEquals(1, results.count { it.goalReached })
        assertEquals(240, results.indexOfFirst { it.goalReached } + 1) // exatamente em 2 h
    }

    @Test
    fun `apliquei protetor zera e permite novo alerta`() {
        val (s, _) = ticks(begin(), start, 250, 50_000f)
        assertTrue(s.goalNotified)
        val now = start + 250 * tick
        val applied = SunExposure.applySunscreen(s, now, zone)
        assertEquals(0L, applied.sinceApplicationMs)
        assertFalse(applied.goalNotified)
        assertEquals(now, applied.lastApplicationAt)
        assertEquals(s.todayMs, applied.todayMs)
        val (_, results) = ticks(applied, now, 240, 50_000f)
        assertEquals(1, results.count { it.goalReached })
    }

    @Test
    fun `virada da meia noite divide o intervalo e fecha o dia`() {
        val beforeMidnight = LocalDateTime.of(2026, 1, 10, 23, 59, 50).atZone(zone).toInstant().toEpochMilli()
        val s0 = SunState(
            epochDay = SunExposure.dayOf(beforeMidnight, zone),
            todayMs = 1000, sinceApplicationMs = 1000,
            lastTickAt = beforeMidnight, goalNotified = true,
        )
        val r = SunExposure.onTick(s0, beforeMidnight + tick, 50_000f, true, cfg, zone)
        val closed = r.closedDay
        assertNotNull(closed)
        assertEquals(1000 + 10_000L, closed!!.totalMs)
        assertEquals(20_000L, r.state.todayMs)
        assertEquals(20_000L, r.state.sinceApplicationMs)
        assertFalse(r.state.goalNotified)
        assertEquals(s0.epochDay + 1, r.state.epochDay)
    }

    @Test
    fun `normalized troca de dia sem contar tempo`() {
        val s0 = SunState(epochDay = SunExposure.dayOf(start, zone), todayMs = 5000, sinceApplicationMs = 5000)
        val n = SunExposure.normalized(s0, start + 24 * 3600_000L, zone)
        assertEquals(0L, n.todayMs)
        assertNull(SunExposure.onTick(n, start + 24 * 3600_000L, 50_000f, true, cfg, zone).closedDay)
    }

    // ---------------- UV (etapa 2) ----------------

    @Test
    fun `com UV abaixo de 3 o tempo nao conta para reaplicar mas conta no total`() {
        var s = begin()
        repeat(120) { i -> s = SunExposure.onTick(s, start + (i + 1) * tick, 50_000f, true, cfg, zone, uvi = 1.5).state }
        assertEquals(60 * 60_000L, s.todayMs)
        assertEquals(0L, s.sinceApplicationMs)
        assertEquals(1.5 * 0.9, s.doseTodaySed, 1e-9)
    }

    @Test
    fun `dose de UV 10 por 1 h e 9 SED`() {
        var s = begin()
        var dose = 0.0
        repeat(120) { i ->
            val r = SunExposure.onTick(s, start + (i + 1) * tick, 50_000f, true, cfg, zone, uvi = 10.0)
            dose += r.doseSed
            s = r.state
        }
        assertEquals(9.0, s.doseTodaySed, 1e-9)
        assertEquals(9.0, dose, 1e-9)
    }

    private fun doseFor1h(lux: Float, uvi: Double): Double {
        var s = SunExposure.onTick(SunExposure.initial(start, zone), start, lux, true, cfg, zone).state
        repeat(120) { i -> s = SunExposure.onTick(s, start + (i + 1) * tick, lux, true, cfg, zone, uvi = uvi).state }
        return s.doseTodaySed
    }

    @Test
    fun `na sombra a dose soma so parte do UV previsto`() {
        // UV 10 por 1 h = 9 SED no sol direto; 30% na sombra clara e 60% com sol filtrado.
        assertEquals(9.0 * 0.3, doseFor1h(15_000f, 10.0), 1e-9)
        assertEquals(9.0 * 0.6, doseFor1h(30_000f, 10.0), 1e-9)
        assertEquals(9.0, doseFor1h(80_000f, 10.0), 1e-9)
    }

    @Test
    fun `fracao de UV pelos limites de lux`() {
        assertEquals(0.3, SunExposure.uvFraction(24_999f), 0.0)
        assertEquals(0.6, SunExposure.uvFraction(25_000f), 0.0)
        assertEquals(0.6, SunExposure.uvFraction(49_999f), 0.0)
        assertEquals(1.0, SunExposure.uvFraction(50_000f), 0.0)
    }

    @Test
    fun `na sombra o tempo ao sol conta inteiro mesmo com dose menor`() {
        var s = begin()
        repeat(120) { i -> s = SunExposure.onTick(s, start + (i + 1) * tick, 15_000f, true, cfg, zone, uvi = 10.0).state }
        assertEquals(60 * 60_000L, s.todayMs)
        assertEquals(60 * 60_000L, s.sinceApplicationMs)
    }

    @Test
    fun `alerta de dose sem protetor dispara em metade da MED uma vez por dia`() {
        // Tipo II: MED 2,5 SED; metade = 1,25 SED; com UV 10 (9 SED/h) leva ~8,3 min.
        var s = begin()
        val results = mutableListOf<SunTickResult>()
        repeat(60) { i ->
            val r = SunExposure.onTick(s, start + (i + 1) * tick, 50_000f, true, cfg, zone, uvi = 10.0)
            results += r
            s = r.state
        }
        assertEquals(1, results.count { it.doseAlert })
        assertEquals(17, results.indexOfFirst { it.doseAlert } + 1) // 17 ticks de 30 s = 8,5 min
    }

    @Test
    fun `sem alerta de dose quando ja aplicou protetor hoje`() {
        var s = SunExposure.applySunscreen(begin(), start, zone)
        var any = false
        repeat(60) { i ->
            val r = SunExposure.onTick(s, start + (i + 1) * tick, 50_000f, true, cfg, zone, uvi = 10.0)
            any = any || r.doseAlert
            s = r.state
        }
        assertFalse(any)
    }

    @Test
    fun `alerta de UV 8 ou mais no maximo a cada 2 h`() {
        var s = begin()
        val results = mutableListOf<SunTickResult>()
        repeat(300) { i -> // 2,5 h
            val r = SunExposure.onTick(s, start + (i + 1) * tick, 50_000f, true, cfg, zone, uvi = 9.0)
            results += r
            s = r.state
        }
        assertEquals(2, results.count { it.uvAlert })
        assertTrue(results.first().uvAlert)
        assertEquals(241, results.indexOfLast { it.uvAlert } + 1)
    }

    @Test
    fun `sem alerta de UV fora do sol`() {
        val (_, results) = ticks(begin(), start, 20, 2_000f)
        assertFalse(results.any { it.uvAlert || it.doseAlert })
    }

    @Test
    fun `dose zera na virada do dia`() {
        val beforeMidnight = LocalDateTime.of(2026, 1, 10, 23, 59, 50).atZone(zone).toInstant().toEpochMilli()
        val s0 = SunState(epochDay = SunExposure.dayOf(beforeMidnight, zone), doseTodaySed = 3.0, lastTickAt = beforeMidnight)
        val r = SunExposure.onTick(s0, beforeMidnight + tick, 50_000f, true, cfg, zone, uvi = 6.0)
        // 20 s depois da meia-noite com UV 6.
        assertEquals(6.0 * 0.9 * 20_000 / 3_600_000, r.state.doseTodaySed, 1e-9)
    }
}
