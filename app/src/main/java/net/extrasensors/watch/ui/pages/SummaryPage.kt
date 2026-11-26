package net.extrasensors.watch.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.domain.Barometry
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.PressurePoint
import net.extrasensors.watch.domain.RainTrend
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.domain.TrendResult
import net.extrasensors.watch.ui.components.Dot
import net.extrasensors.watch.ui.components.HSpace
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.rememberNow
import java.time.ZoneId

data class SummaryRow(val label: String, val value: String, val accent: Color, val page: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModel(g: AppGraph) : ViewModel() {
    private val ticker = tickerFlow(60_000)

    private val pressurePart = ticker.flatMapLatest { now ->
        combine(
            g.db.pressure().observeSince(now - RainTrend.WINDOW_MS - 2 * RainTrend.TOLERANCE_MS),
            g.settings.settings,
        ) { samples, s ->
            val latest = samples.lastOrNull()?.takeIf { now - it.timeMillis <= 30 * 60_000 }
            val alt = latest?.let { Format.number(Barometry.altitude(s.p0, it.hPa)) + " m" } ?: Format.NO_DATA
            val trend = RainTrend.compute(samples.map { PressurePoint(it.timeMillis, it.hPa) }, now, s.thresholds)
            val rain = (trend as? TrendResult.Available)?.state?.shortLabel ?: Format.NO_DATA
            alt to rain
        }
    }

    private val climbPart = combine(g.live.climb, g.db.climb().observeLatestSession()) { live, last ->
        when {
            live != null -> Format.number(live.gain) + " m"
            last != null -> Format.number(last.gain) + " m"
            else -> Format.NO_DATA
        }
    }

    private val sunPart = combine(g.state.sun, ticker) { s, now ->
        s?.let { Format.duration(SunExposure.normalized(it, now, ZoneId.systemDefault()).sinceApplicationMs) }
            ?: Format.NO_DATA
    }

    private val miscPart = combine(
        g.state.lastDb, g.db.skin().observeLatest(),
        g.db.stress().observeHrvSince(System.currentTimeMillis() - 30L * 86_400_000),
    ) { db, skin, hrv ->
        val dbText = db?.let { Format.number(it.first) + " dB" } ?: Format.NO_DATA
        val skinText = skin?.let { Format.number(it.celsius, 1) + " °C" } ?: Format.NO_DATA
        val hrvText = hrv.lastOrNull()?.let { Format.number(it.rmssdMs) + " ms" } ?: Format.NO_DATA
        Triple(dbText, skinText, hrvText)
    }

    val rows: StateFlow<List<SummaryRow>> =
        combine(pressurePart, climbPart, sunPart, miscPart) { p, climb, sun, misc ->
            listOf(
                SummaryRow("Altitude", p.first, Palette.Baro, 1),
                SummaryRow("Chuva", p.second, Palette.Rain, 2),
                SummaryRow("Subida", climb, Palette.Climb, 3),
                SummaryRow("Sol", sun, Palette.Sun, 5),
                SummaryRow("Som", misc.first, Palette.Sound, 6),
                SummaryRow("Pele", misc.second, Palette.Skin, 7),
                SummaryRow("HRV", misc.third, HrvColor, 9),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun SummaryPage(g: AppGraph, onOpen: (Int) -> Unit) {
    val vm: SummaryViewModel = viewModel { SummaryViewModel(g) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    rememberNow(60_000)
    RoundPage {
        PageLabel("Resumo", Palette.Secondary)
        rows.forEach { row ->
            Button(
                onClick = { onOpen(row.page) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface, contentColor = Palette.Primary),
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).heightIn(min = 48.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(row.accent)
                        HSpace(8.dp)
                        Text(row.label, color = Palette.Support, fontSize = 14.sp)
                    }
                    Text(row.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}
