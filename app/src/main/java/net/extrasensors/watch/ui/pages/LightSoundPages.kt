package net.extrasensors.watch.ui.pages

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.LocalScreenIsActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.data.AppSettings
import net.extrasensors.watch.domain.Bands
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.SoundMath
import net.extrasensors.watch.domain.SoundSession
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.domain.SunState
import net.extrasensors.watch.service.SunActions
import net.extrasensors.watch.ui.components.ActionButton
import net.extrasensors.watch.ui.components.BigValue
import net.extrasensors.watch.ui.components.Loading
import net.extrasensors.watch.ui.components.Meter
import net.extrasensors.watch.ui.components.MeterLegend
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.PermissionGate
import net.extrasensors.watch.ui.components.Pill
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.Secondary
import net.extrasensors.watch.ui.components.StateMessage
import net.extrasensors.watch.ui.components.rememberNow
import net.extrasensors.watch.ui.components.rememberResumed
import net.extrasensors.watch.ui.components.serviceStoppedText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.log10

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private fun timeOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(hhmm)
private fun isToday(millis: Long, now: Long): Boolean {
    val z = ZoneId.systemDefault()
    return SunExposure.dayOf(millis, z) == SunExposure.dayOf(now, z)
}

// ---------------- Luz ambiente ----------------

class LightViewModel(private val g: AppGraph) : ViewModel() {
    val available = g.light.available
    val lux = MutableStateFlow<Float?>(null)
    val settings = g.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    suspend fun runLive() {
        if (!available) return
        g.light.readings().collect { lux.value = it.value }
    }
}

@Composable
fun LightPage(g: AppGraph) {
    val vm: LightViewModel = viewModel { LightViewModel(g) }
    val label = "Luz ambiente"
    if (!vm.available) {
        StateMessage(label, Palette.Light, "Sensor indisponível neste relógio")
        return
    }
    val active = LocalScreenIsActive.current && rememberResumed()
    LaunchedEffect(active) { if (active) vm.runLive() }
    val lux by vm.lux.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (active) { delay(5_000); waited = true } }
    val v = lux
    if (v == null) {
        if (!waited) Loading(label, Palette.Light)
        else StateMessage(label, Palette.Light, "Sem leitura do sensor", "O sensor fica sob o vidro; verifique se nada o cobre.")
        return
    }
    RoundPage {
        PageLabel(label, Palette.Light)
        BigValue(Format.number(v.toDouble()), "lx")
        Secondary(Bands.lightDescription(v.toDouble()), Palette.Primary, 15.sp)
        Meter(
            fraction = Bands.lightFraction(v.toDouble()),
            color = Palette.Light,
            description = "Escala logarítmica de 1 a 100 mil lux",
            marker = true,
            tickAt = (log10(settings.luxThreshold.toDouble()) / 5.0).toFloat(),
        )
        MeterLegend("escuro", "sol direto")
    }
}

// ---------------- Sol (exposição solar) ----------------

class SunViewModel(private val g: AppGraph) : ViewModel() {
    private val uv = tickerFlow(60_000).map { net.extrasensors.watch.sync.WatchSync.currentUv(g, it) }
    val state = combine(g.state.sun, g.settings.settings, g.live.serviceRunning, g.live.onWrist, uv) { s, set, running, wrist, uvi ->
        SunUi(s, set, running, wrist, uvi)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun apply() {
        viewModelScope.launch { SunActions.applySunscreen(g.context) }
    }
}

data class SunUi(val sun: SunState?, val settings: AppSettings, val running: Boolean, val onWrist: Boolean?, val uvi: Double?)

@Composable
fun SunPage(g: AppGraph, openSettings: () -> Unit) {
    val vm: SunViewModel = viewModel { SunViewModel(g) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = "Sol"
    if (!g.light.available) {
        StateMessage(label, Palette.Sun, "Sensor de luz indisponível neste relógio")
        return
    }
    val ui by vm.state.collectAsStateWithLifecycle()
    val now = rememberNow(15_000)
    val u = ui ?: run { Loading(label, Palette.Sun); return }
    if (!u.settings.background) {
        StateMessage(
            label, Palette.Sun, "Medição em segundo plano desligada",
            "A contagem do sol só funciona com ela ligada.", "Abrir Ajustes", openSettings,
        )
        return
    }
    val zone = ZoneId.systemDefault()
    val s = u.sun?.let { SunExposure.normalized(it, now, zone) }
    val since = s?.sinceApplicationMs ?: 0L
    val today = s?.todayMs ?: 0L
    val goal = u.settings.sunConfig.goalMs
    val reached = since >= goal

    RoundPage {
        PageLabel(label, Palette.Sun)
        val big = Format.duration(since)
        val parts = big.split(" ", limit = 2)
        if (since < 60 * 60_000L) BigValue(parts[0], "min") else BigValue(big, null, sizeFraction = 0.17f)
        Secondary("desde o protetor, com UV ≥ 3", Palette.Support, 13.sp, top = 2.dp)
        Meter((since.toFloat() / goal).coerceIn(0f, 1f), Palette.Sun, "Progresso até a meta", marker = false)
        MeterLegend("0", "alerta em ${Format.duration(goal)}")
        if (reached) Pill("Hora de reaplicar protetor", Palette.Sun, filled = true)
        ActionButton("Apliquei protetor", Palette.Sun, vm::apply)
        val uvText = u.uvi?.let { "UV agora ${Format.number(it, 1)} · ${net.extrasensors.shared.UvMath.category(it)}" }
            ?: "Sem índice UV: abra o app no celular"
        Secondary(uvText, if (u.uvi != null) Palette.Primary else Palette.Secondary, 13.sp, top = 8.dp)
        val dose = s?.doseTodaySed ?: 0.0
        val med = u.settings.skinType.medSed
        if (u.uvi != null || dose > 0) {
            Secondary("Dose hoje ${Format.number(dose, 1)} SED · ${(dose / med * 100).toInt()}% da sua MED", Palette.Support, 13.sp)
        }
        Secondary("Hoje: ${Format.duration(today)} no total", top = 4.dp)
        val last = s?.lastApplicationAt
        if (last != null && isToday(last, now)) Secondary("Protetor aplicado às ${timeOf(last)}")
        val status = when {
            !u.running -> serviceStoppedText(context)
            u.onWrist == false -> "Pausado: relógio fora do pulso"
            else -> "Medindo em segundo plano"
        }
        Secondary(status, Palette.Secondary, 12.sp)
    }
}

// ---------------- Decibelímetro ----------------

data class SoundUi(val current: Double, val mean: Double, val peak: Double)

class SoundViewModel(private val g: AppGraph) : ViewModel() {
    val available = g.sound.available
    val ui = MutableStateFlow<SoundUi?>(null)
    val error = MutableStateFlow<String?>(null)

    suspend fun runLive() {
        val offset = g.settings.current().dbOffset
        val session = SoundSession()
        ui.value = null
        error.value = null
        var lastEmit = 0L
        try {
            g.sound.blockRms().collect { rms ->
                session.addBlock(SoundMath.db(rms, offset))
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 250) {
                    lastEmit = now
                    ui.value = SoundUi(session.current, session.mean, session.peak)
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            error.value = t.message ?: "Falha no microfone"
        } finally {
            if (session.hasData) {
                // Grava fora do escopo cancelado.
                g.appScope.launch { g.state.setLastDb(session.current, System.currentTimeMillis()) }
            }
        }
    }
}

@Composable
fun SoundPage(g: AppGraph) {
    val vm: SoundViewModel = viewModel { SoundViewModel(g) }
    val label = "Decibelímetro"
    if (!vm.available) {
        StateMessage(label, Palette.Sound, "Microfone indisponível neste relógio")
        return
    }
    PermissionGate(
        label, Palette.Sound,
        required = listOf(Manifest.permission.RECORD_AUDIO),
        rationale = "O decibelímetro usa o microfone só com esta tela aberta. Nada é gravado.",
    ) {
        val active = LocalScreenIsActive.current && rememberResumed()
        LaunchedEffect(active) { if (active) vm.runLive() }
        val ui by vm.ui.collectAsStateWithLifecycle()
        val err by vm.error.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val e = err
        if (e != null) {
            StateMessage(label, Palette.Sound, "Microfone indisponível", e, "Tentar de novo") {
                scope.launch { vm.runLive() }
            }
            return@PermissionGate
        }
        val u = ui ?: run { Loading(label, Palette.Sound); return@PermissionGate }
        RoundPage {
            PageLabel(label, Palette.Sound)
            BigValue(Format.number(u.current), "dB")
            Secondary(Bands.soundLabel(u.current), Palette.Primary, 15.sp)
            Meter(Bands.soundFraction(u.current), Palette.Sound, "Escala de 30 a 120 dB", marker = true)
            Secondary("média ${Format.number(u.mean)} dB · pico ${Format.number(u.peak)} dB", Palette.Support, 13.sp, top = 8.dp)
            Secondary("valor aproximado", top = 2.dp)
        }
    }
}
