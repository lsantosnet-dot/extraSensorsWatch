package net.extrasensors.phone

import net.extrasensors.phone.uv.OpenMeteo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Usa Robolectric porque org.json vem do Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenMeteoTest {
    @Test
    fun `resposta do Open-Meteo`() {
        val json = """{"hourly":{"time":[1763640000,1763643600,1763647200],"uv_index":[0.0,null,5.35]}}"""
        val h = OpenMeteo.parse(json)
        assertEquals(2, h.size)
        assertEquals(1_763_647_200_000L, h[1].hourStart)
        assertEquals(5.35, h[1].uvi, 1e-9)
        assertTrue(OpenMeteo.url(-23.5505, -46.6333).contains("latitude=-23.55&longitude=-46.63"))
    }
}
