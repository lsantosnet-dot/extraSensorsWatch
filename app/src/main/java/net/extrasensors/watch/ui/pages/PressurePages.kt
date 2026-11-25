package net.extrasensors.watch.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.LocalScreenIsActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.data.AppSettings
import net.extrasensors.watch.domain.Barometry
import net.extrasensors.watch.domain.ExponentialFilter
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.PressurePoint
import net.extrasensors.watch.domain.RainTrend
import net.extrasensors.watch.domain.TrendResult
import net.extrasensors.watch.ui.components.BigValue
import net.extrasensors.watch.ui.components.Loading
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.Pill
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.Secondary
import net.extrasensors.watch.ui.components.SecondaryButton
import net.extrasensors.watch.ui.components.Sparkline
import net.extrasensors.watch.ui.components.StateMessage
import net.extrasensors.watch.ui.components.rememberResumed

data class PressureHistory(
    val samples: List<PressurePoint>,
    val trend: TrendResult,
    val settings: AppSettings,
    val now: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun historyFlow(g: AppGraph) = tickerFlow(60_000).flatMapLatest { now ->
    combine(
        g.db.pressure().observeSince(now - RainTrend.WINDOW_MS - 2 * RainTrend.TOLERANCE_MS),
        g.settings.settings,
    ) { list, s ->
        val pts = list.map { PressurePoint(it.timeMillis, it.hPa) }
        PressureHistory(pts, RainTrend.compute(pts, now, s.thresholds), s, now)
    }
}

/** Últimas 3 h para o mini gráfico. */
private fun PressureHistory.last3h(): List<Double> =
    samples.filter { it.timeMillis >= now - RainTrend.WINDOW_MS }.map { it.hPa }

// ---------------- Barômetro ----------------

class BaroViewModel(private val g: AppGraph) : ViewModel() {
    val available = g.pressure.available
    val history: StateFlow<PressureHistory?> =
        historyFlow(g).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val live = MutableStateFlow<Double?>(null)
    val timedOut = MutableStateFlow(false)

    /** Leitura ao vivo enquanto a tela está visível. */
    suspend fun runLive() {
        if (!available) return
        val f = ExponentialFilter(0.1)
        g.pressure.readings().collect { live.value = f.add(it.value.toDouble()) }
    }
}

@Composable
fun BaroPage(g: AppGraph) {
    val vm: BaroViewModel = viewModel { BaroViewModel(g) }
    val label = "Barômetro"
    if (!vm.available) {
        StateMessage(label, Palette.Baro, "Sensor indisponível neste relógio")
        return
    }
    val active = LocalScreenIsActive.current && rememberResumed()
    LaunchedEffect(active) { if (active) vm.runLive() }
    val history by vm.history.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5_000); waited = true }

    val h = history
    val hPa = live ?: h?.samples?.lastOrNull()?.takeIf { h.now - it.timeMillis <= 30 * 60_000 }?.hPa
    if (hPa == null || h == null) {
        if (!waited) Loading(label, Palette.Baro)
        else StateMessage(label, Palette.Baro, "Sem leitura do sensor", "Abra a tela de novo em alguns segundos.")
        return
    }
    RoundPage {
        PageLabel(label, Palette.Baro)
        BigValue(Format.number(hPa, 1), "hPa", sizeFraction = 0.19f)
        Pill("Altitude ${Format.number(Barometry.altitude(h.settings.p0, hPa))} m", Palette.Baro)
        val pts = h.last3h()
        if (pts.size >= 2) Sparkline(pts, Palette.Baro, "Pressão nas últimas 3 horas")
        val trendText = (h.trend as? TrendResult.Available)?.state?.label?.lowercase()
        Secondary(if (trendText != null) "últimas 3 h · $trendText" else "últimas 3 h · coletando")
    }
}

// ---------------- Chuva (tendência) ----------------

class RainViewModel(g: AppGraph) : ViewModel() {
    val available = g.pressure.available
    val history: StateFlow<PressureHistory?> =
        historyFlow(g).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val serviceRunning = g.live.serviceRunning
}

@Composable
fun RainPage(g: AppGraph, openSettings: () -> Unit) {
    val vm: RainViewModel = viewModel { RainViewModel(g) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = "Tendência de pressão"
    if (!vm.available) {
        StateMessage(label, Palette.Rain, "Sensor indisponível neste relógio")
        return
    }
    val history by vm.history.collectAsStateWithLifecycle()
    val running by vm.serviceRunning.collectAsStateWithLifecycle()
    val h = history ?: run { Loading(label, Palette.Rain); return }
    if (!h.settings.background) {
        StateMessage(
            label, Palette.Rain, "Medição em segundo plano desligada",
            "A tendência precisa de amostras a cada 5 min.", "Abrir Ajustes", openSettings,
        )
        return
    }
    when (val t = h.trend) {
        is TrendResult.Unavailable -> RoundPage {
            PageLabel(label, Palette.Rain)
            Secondary("Coletando dados", Palette.Primary, 17.sp, top = 12.dp)
            Secondary("faltam ${Format.remaining(t.remainingMillis)}", top = 6.dp)
            if (!running) Secondary(net.extrasensors.watch.ui.components.serviceStoppedText(context), Palette.Support, top = 8.dp)
        }
        is TrendResult.Available -> {
            val color = Color(t.state.color)
            RoundPage {
                PageLabel(label, Palette.Rain)
                BigValue(Format.signed(t.delta, 1), "hPa / 3 h", sizeFraction = 0.18f)
                Pill(t.state.label, color, textColor = color)
                val pts = h.last3h()
                if (pts.size >= 2) Sparkline(pts, color, "Pressão nas últimas 3 horas", height = 38.dp)
                Secondary(t.state.message, Palette.Primary, 14.sp, top = 6.dp)
            }
        }
    }
}

// ---------------- Subida acumulada ----------------

@OptIn(ExperimentalCoroutinesApi::class)
class ClimbViewModel(private val g: AppGraph) : ViewModel() {
    val available = g.pressure.available
    val live = g.live.climb
    val lastSession = g.db.climb().observeLatestSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val points = g.db.climb().observeLatestSession().flatMapLatest { s ->
        if (s == null) kotlinx.coroutines.flow.flowOf(emptyList()) else g.db.climb().observePoints(s.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = historyFlow(g).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ClimbPage(g: AppGraph, start: () -> Unit, stop: () -> Unit) {
    val vm: ClimbViewModel = viewModel { ClimbViewModel(g) }
    val label = "Subida acumulada"
    if (!vm.available) {
        StateMessage(label, Palette.Climb, "Sensor indisponível neste relógio")
        return
    }
    val live by vm.live.collectAsStateWithLifecycle()
    val last by vm.lastSession.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val now = net.extrasensors.watch.ui.components.rememberNow(1_000)

    val session = last
    val active = live != null
    if (session == null && !active) {
        RoundPage {
            PageLabel(label, Palette.Climb)
            Secondary("Nenhuma sessão", Palette.Primary, 16.sp, top = 10.dp)
            Secondary("Inicie ao começar a trilha ou o treino.")
            net.extrasensors.watch.ui.components.ActionButton("Iniciar", Palette.Climb, start)
        }
        return
    }
    val gain = live?.gain ?: session?.gain ?: 0.0
    val loss = live?.loss ?: session?.loss ?: 0.0
    val startedAt = live?.startedAt ?: session!!.startedAt
    val endedAt = if (active) now else session?.endedAt ?: now
    val p0 = history?.settings?.p0 ?: Barometry.STANDARD_P0
    val currentAlt = live?.altitude ?: history?.samples?.lastOrNull()
        ?.takeIf { (history?.now ?: 0) - it.timeMillis <= 30 * 60_000 }
        ?.let { Barometry.altitude(p0, it.hPa) }

    RoundPage {
        PageLabel(label, Palette.Climb)
        BigValue(Format.number(gain), "m", sizeFraction = 0.18f)
        Secondary("Descida ${Format.number(loss)} m", Palette.Support, 14.sp, top = 0.dp)
        if (points.size >= 2) {
            Sparkline(
                points.map { it.altitude }, Palette.Climb, "Perfil de altitude da sessão",
                height = 30.dp, fill = Color(0xFF2A1A24), minRange = 5.0,
            )
        }
        val altText = currentAlt?.let { " · altitude atual ${Format.number(it)} m" } ?: ""
        Secondary(Format.clock(endedAt - startedAt) + altText, size = 12.sp)
        if (active) {
            net.extrasensors.watch.ui.components.ActionButton("Parar", Palette.Climb, stop)
        } else {
            net.extrasensors.watch.ui.components.ActionButton("Nova sessão", Palette.Climb, { confirm = true })
        }
    }
    androidx.wear.compose.material3.AlertDialog(
        visible = confirm,
        onDismissRequest = { confirm = false },
        title = { androidx.wear.compose.material3.Text("Zerar e iniciar nova sessão?") },
        confirmButton = {
            androidx.wear.compose.material3.AlertDialogDefaults.ConfirmButton(onClick = {
                confirm = false
                start()
            })
        },
    )
}
