package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SoundSkinFormatTest {

    @Test
    fun `rms conhecido para dB`() {
        assertEquals(90.0, SoundMath.db(32767.0, 90.0), 1e-9)
        assertEquals(70.0, SoundMath.db(3276.7, 90.0), 1e-9)
        val square = ShortArray(1600) { if (it % 2 == 0) 1000 else -1000 }
        assertEquals(1000.0, SoundMath.rms(square), 1e-9)
    }

    @Test
    fun `rms zero vira piso de 0 dB`() {
        assertEquals(0.0, SoundMath.db(0.0, 90.0), 1e-9)
        assertEquals(0.0, SoundMath.rms(ShortArray(100)), 1e-9)
    }

    @Test
    fun `leq e diferente da media aritmetica`() {
        val leq = SoundMath.leq(listOf(40.0, 80.0))
        assertEquals(77.0, leq, 0.05) // dominado pelo nível mais alto
        assertTrue(leq > 60.0)
    }

    @Test
    fun `sessao guarda pico e media energetica`() {
        val s = SoundSession()
        listOf(40.0, 40.0, 80.0).forEach(s::addBlock)
        assertEquals(80.0, s.peak, 1e-9)
        assertEquals(SoundMath.leq(listOf(40.0, 40.0, 80.0)), s.mean, 1e-9)
    }

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 1, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `media da pele exige 3 dias e ignora hoje`() {
        val now = at(20, 12)
        val two = listOf(TempReading(at(18, 10), 33.0), TempReading(at(19, 10), 33.4), TempReading(at(20, 9), 40.0))
        assertEquals(SkinBaseline.Insufficient(2, 3), SkinTemperature.baseline(two, now, zone))
        val three = two + TempReading(at(17, 10), 33.2)
        val b = SkinTemperature.baseline(three, now, zone) as SkinBaseline.Available
        assertEquals((33.0 + 33.4 + 33.2) / 3, b.mean, 1e-9)
    }

    @Test
    fun `media da pele usa so os 7 dias anteriores e media por dia`() {
        val now = at(20, 12)
        val r = listOf(
            TempReading(at(12, 10), 20.0), // 8 dias atrás: fora
            TempReading(at(13, 10), 33.0),
            TempReading(at(14, 10), 34.0), TempReading(at(14, 18), 36.0), // média do dia 35
            TempReading(at(19, 10), 32.0),
        )
        val b = SkinTemperature.baseline(r, now, zone) as SkinBaseline.Available
        assertEquals((33.0 + 35.0 + 32.0) / 3, b.mean, 1e-9)
    }

    @Test
    fun `formatacao pt-BR`() {
        assertEquals("915,2", Format.number(915.24, 1))
        assertEquals("10.000", Format.number(10_000.0))
        assertEquals("−4,2", Format.signed(-4.2))
        assertEquals("+0,3", Format.signed(0.3))
        assertEquals("0,0", Format.signed(-0.01))
        assertEquals("38 min", Format.duration(38 * 60_000L))
        assertEquals("1 h 12", Format.duration(72 * 60_000L))
        assertEquals("2 h", Format.duration(120 * 60_000L))
        assertEquals("1 h 05", Format.duration(65 * 60_000L))
        assertEquals("24:10", Format.clock((24 * 60 + 10) * 1000L))
    }

    @Test
    fun `faixas de luz e som`() {
        assertEquals("Interior bem iluminado", Bands.lightDescription(320.0))
        assertEquals("Sol direto", Bands.lightDescription(60_000.0))
        assertEquals(0.4f, Bands.lightFraction(100.0), 1e-6f)
        assertEquals("Silencioso", Bands.soundLabel(39.9))
        assertEquals("Moderado", Bands.soundLabel(40.0))
        assertEquals("Perigoso em exposição longa", Bands.soundLabel(85.0))
    }
}
