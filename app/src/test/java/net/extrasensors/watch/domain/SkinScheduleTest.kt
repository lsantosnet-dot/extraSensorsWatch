package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinScheduleTest {
    private val min = 60_000L
    private val now = 1_000_000_000_000L

    @Test
    fun `espera dobra a cada falha ate o teto e volta ao inicio depois de um acerto`() {
        val b = Backoff(30_000L, 30 * min)
        val seq = List(8) { b.next() }
        assertEquals(listOf(30_000L, 60_000L, 2 * min, 4 * min, 8 * min, 16 * min, 30 * min, 30 * min), seq)
        b.reset()
        assertEquals(30_000L, b.next())
    }

    @Test
    fun `sem leitura gravada mede logo`() {
        assertTrue(SkinSchedule.onDemandDue(supported = true, continuousCollecting = false, lastSavedAt = null, now = now))
    }

    @Test
    fun `leitura recente nao mede de novo`() {
        assertFalse(SkinSchedule.onDemandDue(true, false, now - 29 * min, now))
        assertTrue(SkinSchedule.onDemandDue(true, false, now - 30 * min, now))
    }

    @Test
    fun `enquanto a continua coleta nao mede sob demanda`() {
        assertFalse(SkinSchedule.onDemandDue(true, continuousCollecting = true, lastSavedAt = now - 2 * 60 * min, now = now))
    }

    @Test
    fun `continua parada esperando nova tentativa cai para sob demanda`() {
        // Antes, no modo contínuo, a medição de 30 min nunca rodava e os dados paravam.
        assertTrue(SkinSchedule.onDemandDue(true, continuousCollecting = false, lastSavedAt = now - 45 * min, now = now))
    }

    @Test
    fun `relogio sem sensor de pele nunca mede`() {
        assertFalse(SkinSchedule.onDemandDue(supported = false, continuousCollecting = false, lastSavedAt = null, now = now))
    }
}
