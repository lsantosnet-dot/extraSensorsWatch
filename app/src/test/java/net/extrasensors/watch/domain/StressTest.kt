package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StressTest {

    @Test
    fun `RMSSD de serie alternada conhecida`() {
        // 800, 850, 800, 850…: diferenças de ±50 ms → RMSSD = 50; média 825 ms → 72,7 bpm.
        val ibis = List(60) { if (it % 2 == 0) 800 else 850 }
        val r = Hrv.rmssd(ibis)!!
        assertEquals(50.0, r.rmssdMs, 1e-9)
        assertEquals(60_000.0 / 825.0, r.meanHr, 1e-9)
        assertEquals(60, r.beats)
    }

    @Test
    fun `artefato removido nao gera diferenca falsa`() {
        val clean = List(60) { if (it % 2 == 0) 800 else 850 }
        val withArtifact = clean.toMutableList().apply { add(30, 1600) } // batimento perdido
        assertEquals(Hrv.rmssd(clean)!!.rmssdMs, Hrv.rmssd(withArtifact)!!.rmssdMs, 1e-9)
    }

    @Test
    fun `fora da faixa fisiologica e poucos batimentos`() {
        assertNull(Hrv.rmssd(List(20) { 800 }))
        assertNull(Hrv.rmssd(List(60) { 250 }))
        assertNotNull(Hrv.rmssd(List(40) { 1000 }))
        assertEquals(0.0, Hrv.rmssd(List(40) { 1000 })!!.rmssdMs, 1e-9)
    }

    @Test
    fun `janelas de 10 s com media minimo e maximo`() {
        val agg = EdaAggregator()
        val out = mutableListOf<EdaWindow>()
        for (s in 0 until 25) agg.add(1_000_000L + s * 1000, 2.0 + s * 0.001)?.let(out::add)
        agg.flush()?.let(out::add)
        assertEquals(3, out.size)
        assertEquals(1_000_000L, out[0].timeMillis)
        assertEquals(2.0, out[0].min, 1e-9)
        assertEquals(2.009, out[0].max, 1e-9)
    }

    @Test
    fun `conta picos de ativacao e ignora ruido`() {
        val agg = EdaAggregator()
        // Base 2,0 com ruído de ±0,01 e dois picos de +0,2 µS.
        val signal = (0 until 60).map { s ->
            val noise = if (s % 2 == 0) 0.01 else -0.01
            val peak = when (s) { in 10..13 -> 0.2; in 40..43 -> 0.2; else -> 0.0 }
            2.0 + noise + peak
        }
        val out = mutableListOf<EdaWindow>()
        signal.forEachIndexed { s, v -> agg.add(s * 1000L, v)?.let(out::add) }
        agg.flush()?.let(out::add)
        assertEquals(2, out.sumOf { it.peaks })
    }
}
