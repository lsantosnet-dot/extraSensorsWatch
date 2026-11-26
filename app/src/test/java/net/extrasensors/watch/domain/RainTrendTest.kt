package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainTrendTest {
    private val t = TrendThresholds()
    private val min = 60_000L
    private val now = 1_000_000_000_000L

    @Test
    fun `limites exatos da classificacao`() {
        assertEquals(TrendState.RAPID_FALL, RainTrend.classify(-4.0, t))
        assertEquals(TrendState.FALLING, RainTrend.classify(-3.99, t))
        assertEquals(TrendState.FALLING, RainTrend.classify(-1.5, t))
        assertEquals(TrendState.STABLE, RainTrend.classify(-1.49, t))
        assertEquals(TrendState.STABLE, RainTrend.classify(1.49, t))
        assertEquals(TrendState.RISING, RainTrend.classify(1.5, t))
    }

    private fun series(hours: Int, from: Double, to: Double): List<PressurePoint> {
        val n = hours * 12
        return (0..n).map { i ->
            PressurePoint(now - (n - i) * 5 * min, from + (to - from) * i / n)
        }
    }

    @Test
    fun `queda de 4,2 hPa em 3 h`() {
        val r = RainTrend.compute(series(3, 1012.0, 1007.8), now, t)
        r as TrendResult.Available
        assertEquals(-4.2, r.delta, 1e-9)
        assertEquals(TrendState.RAPID_FALL, r.state)
    }

    /** Série estável de 3 h em [base] hPa, com um degrau [step] (hPa) somado a partir da amostra [at]. */
    private fun withStep(base: Double, at: Int, step: Double, steps: Int = 1): List<PressurePoint> {
        val n = 36
        return (0..n).map { i ->
            val applied = when {
                i < at -> 0.0
                i >= at + steps - 1 -> step
                else -> step * (i - at + 1) / steps
            }
            PressurePoint(now - (n - i) * 5 * min, base + applied)
        }
    }

    @Test
    fun `degrau de altitude no meio da janela nao vira queda rapida`() {
        // Subir ~35 m de uma vez: −4,2 hPa entre duas amostras de 5 min, com o tempo parado.
        val r = RainTrend.compute(withStep(1012.0, at = 18, step = -4.2), now, t)
        r as TrendResult.Available
        assertEquals(0.0, r.delta, 1e-9)
        assertEquals(TrendState.STABLE, r.state)
    }

    @Test
    fun `descer uma ladeira nao vira subindo`() {
        val r = RainTrend.compute(withStep(1008.0, at = 10, step = 3.0), now, t)
        r as TrendResult.Available
        assertEquals(TrendState.STABLE, r.state)
    }

    @Test
    fun `subida gradual em 15 min tambem e descontada`() {
        // −4,2 hPa espalhados em 3 intervalos de 5 min (−1,4 cada): ainda é deslocamento.
        val r = RainTrend.compute(withStep(1012.0, at = 20, step = -4.2, steps = 3), now, t)
        r as TrendResult.Available
        assertEquals(TrendState.STABLE, r.state)
    }

    @Test
    fun `queda real de tempo continua detectada mesmo com degrau de altitude`() {
        // Queda de 4,2 hPa em 3 h e, no meio, uma descida de ladeira de +3 hPa.
        val pts = series(3, 1012.0, 1007.8).mapIndexed { i, p -> if (i >= 18) p.copy(hPa = p.hPa + 3.0) else p }
        val r = RainTrend.compute(pts, now, t)
        r as TrendResult.Available
        // O intervalo de 5 min do degrau é descartado inteiro, junto com sua parte do clima (4,2/36).
        assertEquals(-4.2 + 4.2 / 36, r.delta, 1e-6)
        assertEquals(TrendState.RAPID_FALL, r.state)
    }

    @Test
    fun `lacuna longa entre amostras aceita variacao proporcional de tempo`() {
        // Três amostras com 1 h 30 de intervalo: −2 hPa por trecho é clima (limiar 9 hPa), não degrau.
        val pts = listOf(
            PressurePoint(now - 180 * min, 1012.0),
            PressurePoint(now - 90 * min, 1010.0),
            PressurePoint(now, 1008.0),
        )
        val r = RainTrend.compute(pts, now, t)
        r as TrendResult.Available
        assertEquals(-4.0, r.delta, 1e-9)
        assertEquals(TrendState.RAPID_FALL, r.state)
    }

    @Test
    fun `menos de 3 h de historico fica indisponivel com tempo restante`() {
        val r = RainTrend.compute(series(1, 1012.0, 1011.0), now, t)
        r as TrendResult.Unavailable
        assertEquals(2 * 60 * min, r.remainingMillis)
    }

    @Test
    fun `sem amostras fica indisponivel`() {
        val r = RainTrend.compute(emptyList(), now, t)
        assertEquals(TrendResult.Unavailable(RainTrend.WINDOW_MS), r)
    }

    @Test
    fun `amostra de 3 h atras fora da tolerancia de 10 min`() {
        val pts = listOf(
            PressurePoint(now - 3 * 60 * min - 11 * min, 1010.0),
            PressurePoint(now, 1005.0),
        )
        assertTrue(RainTrend.compute(pts, now, t) is TrendResult.Unavailable)
    }

    @Test
    fun `amostra atual velha demais fica indisponivel`() {
        val pts = series(4, 1012.0, 1008.0).map { it.copy(timeMillis = it.timeMillis - 20 * min) }
        assertTrue(RainTrend.compute(pts, now, t) is TrendResult.Unavailable)
    }

    @Test
    fun `alerta dispara uma vez e respeita histerese e intervalo de 6 h`() {
        val fall = TrendResult.Available(-4.5, TrendState.RAPID_FALL)
        var (s, fire) = RainAlert.evaluate(fall, RainAlertState(), now, t)
        assertTrue(fire)
        assertFalse(s.armed)

        // Continua em queda rápida: não repete.
        RainAlert.evaluate(fall, s, now + 5 * min, t).also { (s2, f) -> assertFalse(f); s = s2 }

        // Melhora só até −3,5 (abaixo do rearme −3,0): continua desarmado.
        RainAlert.evaluate(TrendResult.Available(-3.5, TrendState.FALLING), s, now + 60 * min, t).also { (s2, f) ->
            assertFalse(f); assertFalse(s2.armed); s = s2
        }
        // Passa de −3,0: rearma.
        RainAlert.evaluate(TrendResult.Available(-2.0, TrendState.FALLING), s, now + 90 * min, t).also { (s2, f) ->
            assertFalse(f); assertTrue(s2.armed); s = s2
        }
        // Nova queda antes de 6 h: não alerta.
        RainAlert.evaluate(fall, s, now + 5 * 60 * min, t).also { (s2, f) -> assertFalse(f); s = s2 }
        // Depois de 6 h: alerta.
        RainAlert.evaluate(fall, s, now + 6 * 60 * min, t).also { (_, f) -> assertTrue(f) }
    }

    @Test
    fun `sem dados o alerta nao muda`() {
        val st = RainAlertState(armed = false, lastAlertAt = now)
        val (s, fire) = RainAlert.evaluate(TrendResult.Unavailable(1000), st, now, t)
        assertFalse(fire)
        assertEquals(st, s)
    }
}
