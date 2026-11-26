package net.extrasensors.phone.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.extrasensors.phone.PhoneGraph
import net.extrasensors.phone.data.EdaPointRow
import net.extrasensors.phone.data.EdaSessionRow
import net.extrasensors.phone.data.HrvRow
import net.extrasensors.phone.data.SkinRow
import net.extrasensors.phone.data.SunBucketRow
import net.extrasensors.phone.data.SunDayRow
import net.extrasensors.phone.data.SunscreenRow
import net.extrasensors.phone.data.UvRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.sin

/**
 * Renderiza a tela do celular para conferir os gráficos. Os dados são inseridos só neste
 * teste. Imagens em app-celular/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1400dp-xhdpi")
class PhoneRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun seed(g: PhoneGraph) = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dao = g.db.dao()
        for (d in 0..29) {
            val day = today.minusDays(d.toLong())
            val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val minutes = max(0.0, 40 + 35 * sin(d * 0.7))
            dao.upsertDays(listOf(SunDayRow(day.toEpochDay(), (minutes * 60_000).toLong())))
            for (h in 6..18) {
                val uv = max(0.0, 10.5 * sin((h - 6) / 12.0 * Math.PI)) * (0.7 + 0.3 * sin(d.toDouble()))
                dao.upsertUv(listOf(UvRow(day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(), uv)))
            }
            for (k in 0 until (minutes / 5).toInt()) {
                val start = noon - 90 * 60_000L + k * 15 * 60_000L
                dao.upsertBuckets(listOf(SunBucketRow(start, 300_000, 0.6, 8.0)))
            }
            dao.upsertSkin((0..5).map { i ->
                SkinRow(day.atTime(2 + i * 4, 0).atZone(zone).toInstant().toEpochMilli(), 33.2 + 0.4 * sin(i + d * 0.3))
            })
            if (d % 3 == 0) dao.upsertSunscreen(listOf(SunscreenRow(noon - 3_600_000L)))
            val wake = day.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            dao.upsertHrv(listOf(HrvRow(wake, 45 + 10 * sin(d * 0.9) - (if (d == 1) 18 else 0), 60 + 3 * sin(d.toDouble()), 62, 60_000)))
            if (d == 2) {
                val st = day.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
                dao.upsertEdaSessions(listOf(EdaSessionRow(st, st + 20 * 60_000L, 0)))
                dao.upsertEdaPoints((0 until 120).map { i ->
                    val v = 2.0 + 0.4 * sin(i / 15.0) + (if (i % 17 == 0) 0.3 else 0.0)
                    EdaPointRow(st + i * 10_000L, st, v, v - 0.05, v + 0.08, if (i % 17 == 0) 1 else 0)
                })
            }
        }
    }

    private fun shot(name: String, tab: String?, chip: String?, click: String? = null) {
        val g = PhoneGraph(ApplicationProvider.getApplicationContext())
        seed(g)
        val vm = PhoneViewModel(g)
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { ScreenForTest(vm) } }
        compose.waitForIdle()
        if (chip != null) compose.onNodeWithText(chip).performClick()
        if (tab != null) compose.onNodeWithText(tab).performClick()
        if (click != null) { compose.waitForIdle(); Thread.sleep(500); compose.waitForIdle(); compose.onNodeWithText(click).performClick() }
        // O Room consulta em outra thread: espera de verdade, não só o relógio do Compose.
        repeat(15) {
            Thread.sleep(200)
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        val out = File("build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun sol30dias() = shot("sol-30dias", null, "30 dias")
    @Test fun solHoje() = shot("sol-hoje", null, "Hoje")
    @Test fun pele7dias() = shot("pele-7dias", "Pele", null)
    @Test fun estresse30dias() = shot("estresse-30dias", "Estresse", "30 dias")
    @Test fun estresseGuia() = shot("estresse-guia", "Estresse", null, click = "Como usar e analisar HRV e EDA")
}
