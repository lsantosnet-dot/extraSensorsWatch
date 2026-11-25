package net.extrasensors.watch.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class ClimbAndPressureTest {

    private fun run(values: List<Double>) = values.fold(ClimbAccumulator()) { a, v -> a.add(v) }

    @Test
    fun `subida lenta e contada inteira`() {
        val alts = (0..1000).map { 800.0 + it * 0.1 } // 0,1 m por amostra, 100 m no total
        val acc = run(alts)
        assertEquals(100.0, acc.gain, 2.0)
        assertEquals(0.0, acc.loss, 1e-9)
    }

    @Test
    fun `ruido puro de mais ou menos 1,5 m nao soma nada`() {
        val alts = (0..2000).map { 852.0 + 1.5 * sin(it * 1.7) }
        val acc = run(alts)
        assertEquals(0.0, acc.gain, 1e-9)
        assertEquals(0.0, acc.loss, 1e-9)
    }

    @Test
    fun `subida e descida`() {
        val up = (0..300).map { 500.0 + it }
        val down = (1..200).map { 800.0 - it }
        val acc = run(up + down)
        assertEquals(300.0, acc.gain, 2.0)
        assertEquals(200.0, acc.loss, 2.0)
    }

    @Test
    fun `degrau unico de 2 m conta`() {
        val acc = run(listOf(100.0, 102.0))
        assertEquals(2.0, acc.gain, 1e-9)
    }

    @Test
    fun `degrau de 1,9 m nao conta`() {
        val acc = run(listOf(100.0, 101.9))
        assertEquals(0.0, acc.gain, 1e-9)
    }

    @Test
    fun `calibracao por altitude conhecida ida e volta`() {
        for (p in listOf(850.0, 915.2, 1013.25, 1030.0)) {
            for (h in listOf(-100.0, 0.0, 852.0, 2500.0)) {
                val p0 = Barometry.p0FromAltitude(p, h)
                assertEquals(h, Barometry.altitude(p0, p), 1e-6)
            }
        }
    }

    @Test
    fun `altitude padrao ao nivel do mar e zero`() {
        assertEquals(0.0, Barometry.altitude(Barometry.STANDARD_P0, Barometry.STANDARD_P0), 1e-9)
    }

    @Test
    fun `mediana`() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
    }
}
