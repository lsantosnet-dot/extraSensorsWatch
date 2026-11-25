package net.extrasensors.watch.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.data.AppSettings
import net.extrasensors.watch.data.SettingsRepository
import net.extrasensors.watch.domain.Barometry
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.service.ServiceController
import net.extrasensors.watch.ui.components.PageLabel
import net.extrasensors.watch.ui.components.Palette
import net.extrasensors.watch.ui.components.RoundPage
import net.extrasensors.watch.ui.components.Secondary
import net.extrasensors.watch.ui.components.SecondaryButton
import kotlin.math.abs
import kotlin.math.roundToLong

/** Configurações numéricas editáveis por stepper e coroa. */
enum class NumericSetting(
    val title: String,
    val unit: String,
    val step: Double,
    val min: Double,
    val max: Double,
    val decimals: Int,
) {
    P0("Pressão ao nível do mar", "hPa", 0.25, 950.0, 1050.0, 2),
    RAPID_FALL("Limiar de queda rápida", "hPa / 3 h", 0.5, -10.0, -2.0, 1),
    BAND("Limiar caindo / subindo", "± hPa", 0.5, 0.5, 4.0, 1),
    LUX("Limite de luz forte", "lx", 1000.0, 2000.0, 50000.0, 0),
    GOAL("Meta de exposição", "min", 15.0, 30.0, 240.0, 0),
    DB_OFFSET("Offset do decibelímetro", "dB", 0.5, 60.0, 120.0, 1),
    HEIGHT("Altura", "cm", 1.0, 100.0, 230.0, 0),
    WEIGHT("Peso", "kg", 0.5, 30.0, 250.0, 1),
    AGE("Idade", "anos", 1.0, 10.0, 100.0, 0),
    ;

    fun get(s: AppSettings): Double? = when (this) {
        P0 -> s.p0
        RAPID_FALL -> s.rapidFall
        BAND -> s.band
        LUX -> s.luxThreshold.toDouble()
        GOAL -> s.sunGoalMin.toDouble()
        DB_OFFSET -> s.dbOffset
        HEIGHT -> s.heightCm?.toDouble()
        WEIGHT -> s.weightKg?.toDouble()
        AGE -> s.age?.toDouble()
    }

    /** Valor inicial do editor quando ainda não há valor salvo. */
    val initial: Double
        get() = when (this) {
            HEIGHT -> 170.0
            WEIGHT -> 70.0
            AGE -> 35.0
            else -> min
        }

    suspend fun set(r: SettingsRepository, v: Double) = when (this) {
        P0 -> r.setP0(v)
        RAPID_FALL -> r.setRapidFall(v)
        BAND -> r.setBand(v)
        LUX -> r.setLuxThreshold(v.toFloat())
        GOAL -> r.setSunGoalMin(v.toInt())
        DB_OFFSET -> r.setDbOffset(v)
        HEIGHT -> r.setHeight(v.toFloat())
        WEIGHT -> r.setWeight(v.toFloat())
        AGE -> r.setAge(v.toInt())
    }

    fun format(v: Double?): String = when {
        v == null -> "não definido"
        this == GOAL -> Format.duration((v * 60_000).toLong())
        this == BAND -> "± " + Format.number(v, decimals) + " hPa"
        else -> Format.number(v, decimals) + " " + unit
    }
}

/** Página 9 do pager: atalho para a lista de ajustes (fora do pager, onde a coroa rola a lista). */
@Composable
fun SettingsPage(open: () -> Unit, about: () -> Unit) {
    RoundPage {
        PageLabel("Ajustes", Palette.Secondary)
        Secondary("Limiares, metas, calibração e perfil", Palette.Support, 14.sp, top = 10.dp)
        SecondaryButton("Abrir ajustes", open)
        SecondaryButton("Sobre as medições", about)
    }
}

@Composable
fun SettingsListScreen(
    g: AppGraph,
    onEdit: (NumericSetting) -> Unit,
    onCalibrate: () -> Unit,
    onGender: () -> Unit,
    onAbout: () -> Unit,
) {
    val s by g.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Ajustes") } }

            item { ListHeader { Text("Barômetro e chuva", color = Palette.Baro) } }
            item { SettingButton(NumericSetting.P0.title, NumericSetting.P0.format(s.p0)) { onEdit(NumericSetting.P0) } }
            item { SettingButton("Calibrar por altitude conhecida", "calcula p0 a partir da pressão atual", onCalibrate) }
            item { SettingButton(NumericSetting.RAPID_FALL.title, NumericSetting.RAPID_FALL.format(s.rapidFall)) { onEdit(NumericSetting.RAPID_FALL) } }
            item { SettingButton(NumericSetting.BAND.title, NumericSetting.BAND.format(s.band)) { onEdit(NumericSetting.BAND) } }
            item {
                Toggle("Alerta de chuva", s.rainAlert) { v -> scope.launch { g.settings.setRainAlert(v) } }
            }

            item { ListHeader { Text("Sol", color = Palette.Sun) } }
            item { SettingButton(NumericSetting.LUX.title, NumericSetting.LUX.format(s.luxThreshold.toDouble())) { onEdit(NumericSetting.LUX) } }
            item { SettingButton(NumericSetting.GOAL.title, NumericSetting.GOAL.format(s.sunGoalMin.toDouble())) { onEdit(NumericSetting.GOAL) } }
            item {
                Toggle("Alerta de protetor", s.sunAlert) { v -> scope.launch { g.settings.setSunAlert(v) } }
            }

            item { ListHeader { Text("Som", color = Palette.Sound) } }
            item { SettingButton(NumericSetting.DB_OFFSET.title, NumericSetting.DB_OFFSET.format(s.dbOffset)) { onEdit(NumericSetting.DB_OFFSET) } }

            item { ListHeader { Text("Perfil (perda de suor)", color = Palette.Sweat) } }
            item { SettingButton(NumericSetting.HEIGHT.title, NumericSetting.HEIGHT.format(s.heightCm?.toDouble())) { onEdit(NumericSetting.HEIGHT) } }
            item { SettingButton(NumericSetting.WEIGHT.title, NumericSetting.WEIGHT.format(s.weightKg?.toDouble())) { onEdit(NumericSetting.WEIGHT) } }
            item {
                SettingButton(
                    "Sexo",
                    when (s.gender) { 1 -> "Masculino"; 0 -> "Feminino"; else -> "não definido" },
                    onGender,
                )
            }
            item { SettingButton(NumericSetting.AGE.title, NumericSetting.AGE.format(s.age?.toDouble())) { onEdit(NumericSetting.AGE) } }

            item { ListHeader { Text("Geral") } }
            item {
                Toggle("Medição em segundo plano", s.background) { v ->
                    scope.launch {
                        g.settings.setBackground(v)
                        if (v) ServiceController.startIfEnabled(g.context) else ServiceController.stopBackground(g.context)
                    }
                }
            }
            item { SettingButton("Sobre as medições", null, onAbout) }
        }
    }
}

@Composable
private fun SettingButton(label: String, value: String?, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        secondaryLabel = value?.let { { Text(it, color = Palette.Support) } },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
    )
}

/** Editor numérico: botões − / + e coroa giratória. */
@Composable
fun NumericEditor(
    title: String,
    initial: Double,
    step: Double,
    min: Double,
    max: Double,
    format: (Double) -> String,
    note: String? = null,
    onConfirm: (Double) -> Unit,
) {
    var value by remember { mutableDoubleStateOf(initial.coerceIn(min, max)) }
    var rotaryAcc by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    fun change(delta: Int) {
        val n = ((value + delta * step) / step).roundToLong() * step
        val c = n.coerceIn(min, max)
        if (abs(c - value) > 1e-9) {
            value = c
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                rotaryAcc += e.verticalScrollPixels
                val threshold = 40f
                while (rotaryAcc >= threshold) { change(1); rotaryAcc -= threshold }
                while (rotaryAcc <= -threshold) { change(-1); rotaryAcc += threshold }
                true
            }
            .focusRequester(focus)
            .focusable()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Palette.Secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(
            format(value),
            color = Palette.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StepButton("−", "Diminuir") { change(-1) }
            StepButton("+", "Aumentar") { change(1) }
        }
        if (note != null) Secondary(note, top = 6.dp)
        Button(
            onClick = { onConfirm(value) },
            modifier = Modifier.padding(top = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary, contentColor = Palette.Background),
        ) { Text("Salvar", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(symbol, fontSize = 24.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
fun EditSettingScreen(g: AppGraph, setting: NumericSetting, done: () -> Unit) {
    val s by g.settings.settings.collectAsStateWithLifecycle(null)
    val current = s ?: return
    val scope = rememberCoroutineScope()
    NumericEditor(
        title = setting.title,
        initial = setting.get(current) ?: setting.initial,
        step = setting.step,
        min = setting.min,
        max = setting.max,
        format = { setting.format(it) },
    ) { v ->
        scope.launch {
            setting.set(g.settings, v)
            done()
        }
    }
}

/** Calibração: o usuário informa a altitude conhecida e o app calcula p0 com a pressão atual. */
@Composable
fun CalibrateScreen(g: AppGraph, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pressure by remember { mutableStateOf<Double?>(null) }
    var failed by remember { mutableStateOf(false) }
    var initialAlt by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val p = if (g.pressure.available) g.pressure.sampleMedian(3_000) else null
        if (p == null) failed = true
        else {
            pressure = p
            initialAlt = Barometry.altitude(g.settings.current().p0, p).let { Math.round(it).toDouble() }
        }
    }
    when {
        failed -> RoundPage {
            PageLabel("Calibrar", Palette.Baro)
            Secondary("Sem leitura do barômetro", Palette.Primary, 15.sp, top = 10.dp)
            SecondaryButton("Voltar", done)
        }
        pressure == null || initialAlt == null -> net.extrasensors.watch.ui.components.Loading("Calibrar", Palette.Baro)
        else -> NumericEditor(
            title = "Altitude conhecida",
            initial = initialAlt!!,
            step = 1.0,
            min = -500.0,
            max = 9000.0,
            format = { Format.number(it) + " m" },
            note = "Pressão atual ${Format.number(pressure!!, 1)} hPa",
        ) { alt ->
            scope.launch {
                g.settings.setP0(Barometry.p0FromAltitude(pressure!!, alt))
                done()
            }
        }
    }
}

@Composable
fun GenderScreen(g: AppGraph, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    RoundPage {
        PageLabel("Sexo", Palette.Sweat)
        Secondary("Usado só no cálculo da perda de suor.", top = 6.dp)
        SecondaryButton("Feminino", { scope.launch { g.settings.setGender(0); done() } })
        SecondaryButton("Masculino", { scope.launch { g.settings.setGender(1); done() } })
    }
}

@Composable
fun AboutScreen() {
    RoundPage {
        PageLabel("Sobre as medições", Palette.Secondary)
        AboutItem("Sol", "O sensor mede luz visível, não UV. Dia nublado e manga cobrindo o relógio alteram o resultado.")
        AboutItem("Chuva", "Regra prática baseada na pressão do ar. Não é previsão do tempo.")
        AboutItem("Som", "Não é um SPL certificado. Calibre o offset comparando com um medidor no celular.")
        AboutItem("Pele", "Temperatura da pele do pulso. Não é temperatura corporal.")
        AboutItem("Altitude", "Depende da pressão ao nível do mar (p0). Calibre com uma altitude conhecida.")
    }
}

@Composable
private fun AboutItem(title: String, body: String) {
    Text(title, color = Palette.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    Secondary(body, Palette.Support, 13.sp, top = 2.dp)
}
