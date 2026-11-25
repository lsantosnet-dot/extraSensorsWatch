package net.extrasensors.watch.ui

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.runBlocking
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.data.ClimbPointEntity
import net.extrasensors.watch.data.ClimbSessionEntity
import net.extrasensors.watch.data.PressureSampleEntity
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.ui.pages.BaroPage
import net.extrasensors.watch.ui.pages.ClimbPage
import net.extrasensors.watch.ui.pages.RainPage
import net.extrasensors.watch.ui.pages.SummaryPage
import net.extrasensors.watch.ui.pages.SunPage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor
import java.io.File
import java.time.ZoneId

/**
 * Renderiza telas reais em tamanho de relógio redondo para conferir o layout.
 * Os dados inseridos aqui existem só neste teste; nada disso vai para o app.
 * Imagens em app/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class ScreenRenderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var g: AppGraph
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sm = shadowOf(ctx.getSystemService(SensorManager::class.java))
        sm.addSensor(ShadowSensor.newInstance(Sensor.TYPE_PRESSURE))
        sm.addSensor(ShadowSensor.newInstance(Sensor.TYPE_LIGHT))
        g = AppGraph(ctx)
        runBlocking {
            val dao = g.db.pressure()
            for (i in 0..40) {
                dao.insert(PressureSampleEntity(timeMillis = now - (40 - i) * 5 * 60_000L, hPa = 919.6 - i * 0.11))
            }
            val id = g.db.climb().insertSession(ClimbSessionEntity(startedAt = now - 84 * 60_000L, endedAt = now, gain = 312.0, loss = 298.0))
            val profile = listOf(840.0, 846.0, 858.0, 852.0, 868.0, 860.0, 872.0, 850.0)
            profile.forEachIndexed { i, a ->
                g.db.climb().insertPoint(ClimbPointEntity(sessionId = id, timeMillis = now - (8 - i) * 600_000L, altitude = a))
            }
            val zone = ZoneId.systemDefault()
            g.state.updateSun({ SunExposure.initial(now, zone) }) {
                it.copy(todayMs = 72 * 60_000L, sinceApplicationMs = 38 * 60_000L, lastApplicationAt = now - 60 * 60_000L)
            }
            g.state.setLastDb(42.0, now)
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF333333))) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val out = File("build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun summary() = shot("0-resumo") { SummaryPage(g) {} }
    @Test fun baro() = shot("1-barometro") { BaroPage(g) }
    @Test fun rain() = shot("2-chuva") { RainPage(g) {} }
    @Test fun climb() = shot("3-subida") { ClimbPage(g, {}, {}) }
    @Test fun sun() = shot("5-sol") { SunPage(g) {} }
    @Test fun settings() = shot("9-ajustes") { SettingsPage({}, {}) }
    @Test fun editor() = shot("9b-editor") {
        NumericEditor("Meta de exposição", 120.0, 15.0, 30.0, 240.0, { NumericSetting.GOAL.format(it) }) {}
    }
}
