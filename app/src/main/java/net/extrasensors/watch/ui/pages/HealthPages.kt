package net.extrasensors.watch.ui.pages

import android.Manifest
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.LocalScreenIsActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.Permissions
import net.extrasensors.watch.SweatPhase
import net.extrasensors.watch.data.SkinTempEntity
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.SkinBaseline
import net.extrasensors.watch.domain.SkinTemperature
import net.extrasensors.watch.domain.TempReading
import net.extrasensors.watch.sensor.SkinMeasurement
import net.extrasensors.watch.sensor.SkinMode
import net.extrasensors.watch.ui.components.ActionButton
import net.extrasensors.watch.ui.components.BigValue
import net.extrasensors.watch.ui.components.Loading
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.PermissionGate
import net.extrasensors.watch.ui.components.Pill
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.Secondary
import net.extrasensors.watch.ui.components.StateMessage
import net.extrasensors.watch.ui.components.rememberNow
import net.extrasensors.watch.ui.components.rememberResumed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dm = DateTimeFormatter.ofPattern("dd/MM")

// ---------------- Temperatura da pele ----------------

data class SkinUi(val latest: SkinTempEntity?, val baseline: SkinBaseline)

@OptIn(ExperimentalCoroutinesApi::class)
class SkinViewModel(private val g: AppGraph) : ViewModel() {
    val mode = MutableStateFlow<Result<SkinMode>?>(null)
    val measuring = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val serviceError = g.live.skinError

    val ui = tickerFlow(60_000).flatMapLatest { now ->
        combine(
            g.db.skin().observeLatest(),
            g.db.skin().observeSince(now - (SkinTemperature.DAYS + 1) * 24L * 3600_000),
        ) { latest, recent ->
            SkinUi(
                latest,
                SkinTemperature.baseline(recent.map { TempReading(it.timeMillis, it.celsius) }, now, ZoneId.systemDefault()),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun loadMode() {
        mode.value = g.skin.mode()
    }

    fun measure() {
        if (measuring.value) return
        measuring.value = true
        message.value = null
        viewModelScope.launch {
            when (val m = g.skin.measureOnce(30_000)) {
                is SkinMeasurement.Success -> g.db.skin().insert(SkinTempEntity(timeMillis = m.timeMillis, celsius = m.celsius))
                is SkinMeasurement.Failure -> message.value = m.message
            }
            measuring.value = false
        }
    }
}

@Composable
fun SkinPage(g: AppGraph) {
    val context = LocalContext.current
    val label = "Temp. da pele"
    PermissionGate(
        label, Palette.Skin,
        required = Permissions.healthRequired(context),
        optional = Permissions.healthOptional(context),
        rationale = "A temperatura da pele vem dos sensores de saúde da Samsung.",
    ) {
        val vm: SkinViewModel = viewModel { SkinViewModel(g) }
        val active = LocalScreenIsActive.current && rememberResumed()
        LaunchedEffect(active) { if (active) vm.loadMode() }
        val mode by vm.mode.collectAsStateWithLifecycle()
        val ui by vm.ui.collectAsStateWithLifecycle()
        val measuring by vm.measuring.collectAsStateWithLifecycle()
        val msg by vm.message.collectAsStateWithLifecycle()
        val serviceErr by vm.serviceError.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val m = mode ?: run { Loading(label, Palette.Skin); return@PermissionGate }
        val skinMode = m.getOrElse {
            StateMessage(label, Palette.Skin, "Sensores de saúde indisponíveis", it.message, "Tentar de novo") {
                scope.launch { vm.loadMode() }
            }
            return@PermissionGate
        }
        if (skinMode == SkinMode.NONE) {
            StateMessage(label, Palette.Skin, "Este relógio não mede temperatura da pele")
            return@PermissionGate
        }
        val u = ui ?: run { Loading(label, Palette.Skin); return@PermissionGate }
        val latest = u.latest
        RoundPage {
            PageLabel(label, Palette.Skin)
            if (latest == null) {
                Secondary("Sem leituras ainda", Palette.Primary, 16.sp, top = 10.dp)
                Secondary(
                    if (skinMode == SkinMode.CONTINUOUS) "A leitura contínua grava a cada 30 min." else "Toque em Medir agora.",
                )
            } else {
                BigValue(Format.number(latest.celsius, 1), "°C")
                Secondary("Pele do pulso", Palette.Primary, 15.sp)
                when (val b = u.baseline) {
                    is SkinBaseline.Available ->
                        Pill("${Format.signed(latest.celsius - b.mean, 1)} °C vs. sua média", Palette.Skin)
                    is SkinBaseline.Insufficient ->
                        Pill("Calculando sua média · ${b.daysWithData} de ${b.required} dias", Palette.Skin)
                }
                Secondary("medido às " + Instant.ofEpochMilli(latest.timeMillis).atZone(ZoneId.systemDefault()).format(hm))
            }
            Secondary("não é temperatura corporal", top = 6.dp)
            msg?.let { Secondary(it, Palette.Sound, 12.sp) }
            serviceErr?.let { Secondary(it, Palette.Sound, 12.sp) }
            if (skinMode == SkinMode.CONTINUOUS) Secondary("Medição contínua ativa", Palette.Support, 12.sp)
            ActionButton(if (measuring) "Medindo…" else "Medir agora", Palette.Skin, vm::measure, enabled = !measuring)
        }
    }
}

// ---------------- Perda de suor ----------------

class SweatViewModel(g: AppGraph) : ViewModel() {
    val phase = g.live.sweat
    val latest = g.db.sweat().observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profileSet = g.settings.settings.map { it.profile != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val supported = MutableStateFlow<Result<Boolean>?>(null)
    private val sweat = g.sweat

    suspend fun check() {
        supported.value = sweat.isSupported()
    }
}

@Composable
fun SweatPage(g: AppGraph, start: () -> Unit, finish: () -> Unit, reset: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val label = "Perda de suor"
    PermissionGate(
        label, Palette.Sweat,
        required = Permissions.healthRequired(context) + Manifest.permission.ACTIVITY_RECOGNITION,
        optional = Permissions.healthOptional(context),
        rationale = "A estimativa usa os sensores de saúde da Samsung e a contagem de passos.",
    ) {
        val vm: SweatViewModel = viewModel { SweatViewModel(g) }
        val active = LocalScreenIsActive.current && rememberResumed()
        LaunchedEffect(active) { if (active && vm.supported.value?.isSuccess != true) vm.check() }
        val phase by vm.phase.collectAsStateWithLifecycle()
        val latest by vm.latest.collectAsStateWithLifecycle()
        val profileSet by vm.profileSet.collectAsStateWithLifecycle()
        val supported by vm.supported.collectAsStateWithLifecycle()
        val now = rememberNow(1_000)

        val sup = supported ?: run { Loading(label, Palette.Sweat); return@PermissionGate }
        val ok = sup.getOrElse {
            StateMessage(label, Palette.Sweat, "Sensores de saúde indisponíveis", it.message)
            return@PermissionGate
        }
        if (!ok) {
            StateMessage(label, Palette.Sweat, "Este relógio não mede perda de suor")
            return@PermissionGate
        }
        if (profileSet == false && phase is SweatPhase.Idle) {
            StateMessage(
                label, Palette.Sweat, "Configure seu perfil",
                "Altura, peso, sexo e idade são exigidos pelo cálculo.", "Abrir Ajustes", openSettings,
            )
            return@PermissionGate
        }
        RoundPage {
            PageLabel(label, Palette.Sweat)
            when (val p = phase) {
                SweatPhase.Idle, SweatPhase.Starting -> {
                    Secondary("Meça depois de uma corrida", Palette.Primary, 17.sp, top = 12.dp)
                    latest?.let {
                        val d = Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.systemDefault()).format(dm)
                        Secondary("Última medição: ${Format.number(it.liters, 2)} L · $d")
                    }
                    ActionButton(
                        if (p == SweatPhase.Starting) "Iniciando…" else "Iniciar corrida",
                        Palette.Sweat, start, enabled = p == SweatPhase.Idle,
                    )
                }
                is SweatPhase.Running -> {
                    BigValue(Format.clock(now - p.startedAt), null, sizeFraction = 0.17f)
                    Secondary("corrida em andamento", top = 6.dp)
                    ActionButton("Finalizar", Palette.Sweat, finish)
                }
                SweatPhase.Calculating -> {
                    Secondary("Calculando…", Palette.Primary, 17.sp, top = 12.dp)
                    androidx.wear.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                is SweatPhase.Done -> {
                    BigValue(Format.number(p.liters, 2), "L")
                    Secondary("perda de suor estimada", top = 6.dp)
                    ActionButton("Nova medição", Palette.Sweat, reset)
                }
                is SweatPhase.Error -> {
                    Secondary("Não foi possível medir", Palette.Primary, 16.sp, top = 10.dp)
                    Secondary(p.message)
                    ActionButton("Voltar", Palette.Sweat, reset)
                }
            }
        }
    }
}
