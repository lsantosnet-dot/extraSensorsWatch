package net.extrasensors.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTest {

    @Test
    fun `codec ida e volta`() {
        val p = SyncPayload(
            buckets = listOf(SunBucket(1_000, 120_000, 0.35, 7.2, 5_000), SunBucket(2_000, 30_000, 0.0, null, 6_000)),
            days = listOf(SunDayTotal(20_000, 3_600_000, 7_000)),
            skin = listOf(SkinReading(9_000, 33.84)),
            applications = listOf(SunscreenApplication(8_000)),
        )
        assertEquals(p, SyncCodec.decode(SyncCodec.encode(p)))
    }

    @Test
    fun `codec vazio`() {
        val p = SyncPayload(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(SyncCodec.decode(SyncCodec.encode(p)).isEmpty)
    }

    @Test
    fun `recibo pega os maiores valores`() {
        val a = SyncAck(10, 20, 30, 40)
        val b = SyncAck(15, 5, 35, 0)
        assertEquals(SyncAck(15, 20, 35, 40), a.merge(b))
    }

    @Test
    fun `dose de UV`() {
        // UV 10 por 1 h = 9 SED.
        assertEquals(9.0, UvMath.doseSed(10.0, UvMath.HOUR_MS), 1e-9)
        // Pele tipo II (2,5 SED) com UV 10 sem protetor: ~16,7 min.
        val minutes = SkinType.II.medSed / UvMath.doseSed(10.0, 60_000)
        assertEquals(16.7, minutes, 0.1)
        assertEquals(0.0, UvMath.doseSed(-1.0, UvMath.HOUR_MS), 1e-9)
    }

    @Test
    fun `interpolacao horaria`() {
        val h = listOf(UvHour(0, 2.0), UvHour(UvMath.HOUR_MS, 6.0))
        assertEquals(4.0, UvMath.interpolate(h, UvMath.HOUR_MS / 2)!!, 1e-9)
        assertEquals(6.0, UvMath.interpolate(h, UvMath.HOUR_MS + 10)!!, 1e-9)
        assertNull(UvMath.interpolate(h, -1))
        assertNull(UvMath.interpolate(h, 3 * UvMath.HOUR_MS))
        assertNull(UvMath.interpolate(emptyList(), 0))
    }

    @Test
    fun `categorias da OMS`() {
        assertEquals("Baixo", UvMath.category(2.9))
        assertEquals("Moderado", UvMath.category(3.0))
        assertEquals("Alto", UvMath.category(6.0))
        assertEquals("Muito alto", UvMath.category(8.0))
        assertEquals("Extremo", UvMath.category(11.0))
    }

    @Test
    fun `codec v2 com HRV e EDA`() {
        val p = SyncPayload(
            buckets = emptyList(), days = emptyList(), skin = emptyList(), applications = emptyList(),
            hrv = listOf(HrvMeasurement(1_000, 42.5, 61.2, 58, 60_000)),
            edaSessions = listOf(EdaSession(2_000, null, 0, 3_000), EdaSession(4_000, 9_000, 1, 9_000)),
            edaPoints = listOf(EdaPoint(2_010, 2_000, 3.2, 3.0, 3.5, 2)),
        )
        assertEquals(p, SyncCodec.decode(SyncCodec.encode(p)))
        assertEquals(SyncAck(0, 0, 0, 0, 1_000, 9_000, 2_010), SyncAck.of(p))
    }

    @Test
    fun `envio antigo v1 continua legivel`() {
        val p = SyncPayload(listOf(SunBucket(1, 2, 0.1, null, 3)), emptyList(), listOf(SkinReading(5, 33.0)), emptyList())
        assertEquals(p, SyncCodec.decode(SyncCodec.encode(p, version = 1)))
    }

    @Test
    fun `faixa pessoal de HRV`() {
        val day = 86_400_000L
        val now = 30 * day
        val m = listOf(40.0, 45.0, 50.0, 44.0).mapIndexed { i, v -> HrvMeasurement(now - (i + 1) * day, v, 60.0, 60, 60_000) }
        val b = HrvStats.baseline(m, now)!!
        assertEquals(4, b.count)
        assertTrue(b.lowMs < 44.0 && b.highMs > 46.0)
        assertEquals(HrvStatus.NORMAL, b.status(45.0))
        assertEquals(HrvStatus.BELOW, b.status(30.0))
        assertEquals(HrvStatus.ABOVE, b.status(70.0))
        // Poucas medições ou fora da janela de 7 dias: sem faixa.
        assertNull(HrvStats.baseline(m.take(2), now))
        assertNull(HrvStats.baseline(m, now + 20 * day))
    }
}
