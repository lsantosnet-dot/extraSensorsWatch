package net.extrasensors.watch.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.LocalScreenIsActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import net.extrasensors.shared.HrvMeasurement
import net.extrasensors.shared.HrvStats
import net.extrasensors.shared.HrvStatus
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.HrvPhase
import net.extrasensors.watch.Permissions
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.ui.components.ActionButton
import net.extrasensors.watch.ui.components.BigValue
import net.extrasensors.watch.ui.components.Loading
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.PermissionGate
import net.extrasensors.watch.ui.components.Pill
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.Secondary
import net.extrasensors.watch.ui.components.Sparkline
import net.extrasensors.watch.ui.components.StateMessage
import net.extrasensors.watch.ui.components.rememberNow
import net.extrasensors.watch.ui.components.rememberResumed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val HrvColor = Color(0xFF7DD3FC)
val EdaColor = Color(0xFFFDBA74)

private val dmhm = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private fun whenText(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(dmhm)

// ---------------- Recuperação (HRV) ----------------

class HrvViewModel(g: AppGraph) : ViewModel() {
    val phase = g.live.hrv
    val history = g.db.stress().observeHrvSince(System.currentTimeMillis() - 30L * 86_400_000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val supported = kotlinx.coroutines.flow.MutableStateFlow<Result<Boolean>?>(null)
    private val heart = g.heart
    suspend fun check() { supported.value = heart.isSupported() }
}

@Composable
fun HrvPage(g: AppGraph, measure: () -> Unit, reset: () -> Unit) {
    val context = LocalContext.current
    val label = "Recuperação"
    PermissionGate(
        label, HrvColor,
        required = Permissions.healthRequired(context),
        optional = Permissions.healthOptional(context),
        rationale = "A HRV usa os intervalos entre batimentos do sensor de saúde da Samsung.",
    ) {
        val vm: HrvViewModel = viewModel { HrvViewModel(g) }
        val active = LocalScreenIsActive.current && rememberResumed()
        LaunchedEffect(active) { if (active && vm.supported.value?.isSuccess != true) vm.check() }
        val phase by vm.phase.collectAsStateWithLifecycle()
        val history by vm.history.collectAsStateWithLifecycle()
        val supported by vm.supported.collectAsStateWithLifecycle()
        val now = rememberNow(1_000)

        val sup = supported ?: run { Loading(label, HrvColor); return@PermissionGate }
        val ok = sup.getOrElse {
            StateMessage(label, HrvColor, "Sensores de saúde indisponíveis", it.message)
            return@PermissionGate
        }
        if (!ok) {
            StateMessage(label, HrvColor, "Este relógio não oferece batimento contínuo")
            return@PermissionGate
        }
        val h = history ?: run { Loading(label, HrvColor); return@PermissionGate }
        val all = h.map { HrvMeasurement(it.timeMillis, it.rmssdMs, it.meanHr, it.beats, it.durationMs) }
        RoundPage {
            PageLabel("$label · HRV", HrvColor)
            when (val p = phase) {
                is HrvPhase.Measuring -> {
                    val left = ((60_000 - (now - p.startedAt)).coerceAtLeast(0) / 1000)
                    BigValue("$left", "s", sizeFraction = 0.19f)
                    Secondary("Fique parado, sentado ou deitado", Palette.Primary, 14.sp, top = 4.dp)
                    Secondary("${p.beats} batimentos lidos")
                }
                is HrvPhase.Done -> {
                    BigValue(Format.number(p.rmssdMs), "ms")
                    Secondary("RMSSD · ${Format.number(p.meanHr)} bpm", Palette.Support, 14.sp)
                    BaselinePill(all, System.currentTimeMillis(), p.rmssdMs)
                    ActionButton("OK", HrvColor, reset)
                }
                is HrvPhase.Error -> {
                    Secondary("Não foi possível medir", Palette.Primary, 16.sp, top = 10.dp)
                    Secondary(p.message)
                    ActionButton("Tentar de novo", HrvColor, measure)
                }
                HrvPhase.Idle -> {
                    val last = h.lastOrNull()
                    if (last == null) {
                        Secondary("Meça 1 min ao acordar", Palette.Primary, 16.sp, top = 10.dp)
                        Secondary("Sempre no mesmo horário, parado.")
                    } else {
                        BigValue(Format.number(last.rmssdMs), "ms")
                        Secondary("RMSSD · ${whenText(last.timeMillis)}", Palette.Support, 13.sp)
                        BaselinePill(all, last.timeMillis, last.rmssdMs)
                        val recent = h.takeLast(14).map { it.rmssdMs }
                        if (recent.size >= 2) Sparkline(recent, HrvColor, "RMSSD das últimas medições", minRange = 10.0)
                    }
                    ActionButton("Medir agora (1 min)", HrvColor, measure)
                }
            }
        }
    }
}

@Composable
private fun BaselinePill(all: List<HrvMeasurement>, at: Long, rmssd: Double) {
    val b = HrvStats.baseline(all, at)
    if (b == null) {
        val n = all.count { it.timeMillis < at && it.timeMillis >= at - 7 * 86_400_000L }
        Pill("Formando sua faixa · $n de ${HrvStats.MIN_MEASUREMENTS}", HrvColor)
    } else {
        val st = b.status(rmssd)
        val color = when (st) { HrvStatus.BELOW -> Palette.Sound; HrvStatus.NORMAL -> HrvColor; HrvStatus.ABOVE -> Palette.Skin }
        Pill(st.label, color, textColor = color)
        Secondary("faixa ${Format.number(b.lowMs)}–${Format.number(b.highMs)} ms (7 dias)")
    }
}

// ---------------- Ativação (EDA) ----------------

@OptIn(ExperimentalCoroutinesApi::class)
class EdaViewModel(g: AppGraph) : ViewModel() {
    val live = g.live.eda
    val error = g.live.edaError
    val latest = g.db.stress().observeLatestSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val points = g.db.stress().observeLatestSession().flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else g.db.stress().observePoints(s.startedAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val supported = kotlinx.coroutines.flow.MutableStateFlow<Result<Boolean>?>(null)
    private val eda = g.eda
    suspend fun check() { supported.value = eda.isSupported() }
}

@Composable
fun EdaPage(g: AppGraph, start: () -> Unit, stop: () -> Unit) {
    val context = LocalContext.current
    val label = "Ativação · EDA"
    PermissionGate(
        label, EdaColor,
        required = Permissions.edaRequired(context),
        optional = Permissions.healthOptional(context),
        rationale = "A atividade eletrodérmica vem do sensor de saúde da Samsung e exige a permissão adicional de dados de saúde.",
    ) {
        val vm: EdaViewModel = viewModel { EdaViewModel(g) }
        val active = LocalScreenIsActive.current && rememberResumed()
        LaunchedEffect(active) { if (active && vm.supported.value?.isSuccess != true) vm.check() }
        val live by vm.live.collectAsStateWithLifecycle()
        val err by vm.error.collectAsStateWithLifecycle()
        val latest by vm.latest.collectAsStateWithLifecycle()
        val points by vm.points.collectAsStateWithLifecycle()
        val supported by vm.supported.collectAsStateWithLifecycle()
        val now = rememberNow(1_000)

        val sup = supported ?: run { Loading(label, EdaColor); return@PermissionGate }
        val ok = sup.getOrElse {
            StateMessage(label, EdaColor, "Sensores de saúde indisponíveis", it.message)
            return@PermissionGate
        }
        if (!ok) {
            StateMessage(label, EdaColor, "Este relógio não tem sensor de EDA", "Disponível do Galaxy Watch8 em diante.")
            return@PermissionGate
        }
        RoundPage {
            PageLabel(label, EdaColor)
            val l = live
            if (l != null) {
                BigValue(l.current?.let { Format.number(it, 2) } ?: "—", "µS", sizeFraction = 0.18f)
                Secondary("${Format.clock(now - l.startedAt)} · ${Format.number(l.peaksPerMin, 1)} picos/min", Palette.Support, 13.sp)
                if (l.source == 1) Secondary("gravando junto com a corrida")
                if (points.size >= 2 && latest?.startedAt == l.startedAt) {
                    Sparkline(points.takeLast(60).map { it.mean }, EdaColor, "Condutância da pele na sessão", minRange = 0.2)
                }
                if (l.source == 0) ActionButton("Parar", EdaColor, stop)
            } else {
                val s = latest
                if (s != null && s.endedAt != null && points.isNotEmpty()) {
                    val minutes = (s.endedAt - s.startedAt) / 60_000.0
                    val level = points.map { it.mean }.average()
                    BigValue(Format.number(level, 2), "µS", sizeFraction = 0.18f)
                    Secondary(
                        "última sessão · ${Format.duration(s.endedAt - s.startedAt)} · " +
                            "${Format.number(if (minutes > 0) points.sumOf { it.peaks } / minutes else 0.0, 1)} picos/min",
                        Palette.Support, 12.sp,
                    )
                    Sparkline(points.map { it.mean }, EdaColor, "Condutância da pele na última sessão", minRange = 0.2)
                } else {
                    Secondary("Grave durante uma tarefa, reunião ou momento de estresse", Palette.Primary, 14.sp, top = 10.dp)
                }
                err?.let { Secondary(it, Palette.Sound, 12.sp) }
                ActionButton("Iniciar sessão", EdaColor, start)
                Secondary("Compare você com você: o nível muda com temperatura, suor e aperto da pulseira.", size = 11.sp)
            }
        }
    }
}
