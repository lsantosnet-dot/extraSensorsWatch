package net.extrasensors.watch.domain

import net.extrasensors.watch.service.HrvReminder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HrvReminderTest {
    private val z = ZoneId.of("America/Sao_Paulo")

    @Test
    fun `proximo lembrete hoje ou amanha`() {
        val before = ZonedDateTime.of(2026, 11, 26, 6, 30, 0, 0, z)
        assertEquals(ZonedDateTime.of(2026, 11, 26, 7, 0, 0, 0, z), HrvReminder.nextTrigger(before, 420))
        val after = ZonedDateTime.of(2026, 11, 26, 7, 0, 0, 0, z)
        assertEquals(ZonedDateTime.of(2026, 11, 27, 7, 0, 0, 0, z), HrvReminder.nextTrigger(after, 420))
        assertEquals(ZonedDateTime.of(2026, 11, 27, 6, 15, 0, 0, z), HrvReminder.nextTrigger(after, 375))
    }
}
