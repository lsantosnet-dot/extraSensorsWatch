package net.extrasensors.phone.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import net.extrasensors.phone.phoneGraph
import net.extrasensors.phone.stats.Preset
import net.extrasensors.phone.uv.LocationHelper
import net.extrasensors.shared.UvMath
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val g = phoneGraph
        setContent {
            AppTheme {
                val vm: PhoneViewModel = viewModel { PhoneViewModel(g) }
                Screen(vm)
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------- Formatação pt-BR ----------------

private val ptBr: Locale = Locale.forLanguageTag("pt-BR")
private fun num(v: Double, decimals: Int): String {
    val sym = DecimalFormatSymbols(ptBr).apply { decimalSeparator = ','; groupingSeparator = '.' }
    return DecimalFormat(if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0", sym).format(v)
}

private fun duration(ms: Long): String {
    val min = ms / 60_000
    if (min < 60) return "$min min"
    val h = min / 60
    val m = min % 60
    return if (m == 0L) "$h h" else "$h h " + m.toString().padStart(2, '0')
}

private val dateTimeFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun whenText(t: Long, zone: ZoneId) =
    if (t <= 0) "nunca" else Instant.ofEpochMilli(t).atZone(zone).format(dateTimeFmt)

// ---------------- Tela ----------------

/** Ponto de entrada para os testes de renderização. */
@Composable
internal fun ScreenForTest(vm: PhoneViewModel) = Screen(vm)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: PhoneViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.updateUv()
    }
    val bgLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.updateUv()
    }
    LaunchedEffect(Unit) {
        vm.onOpen()
        if (!LocationHelper.permitted(context)) locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sensores do relógio") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sol") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Pele") })
            }
            PeriodSelector(vm)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) SunTab(vm) else SkinTab(vm)
                StatusCard(
                    vm,
                    onUv = {
                        if (LocationHelper.permitted(context)) vm.updateUv()
                        else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    onBackgroundLocation = {
                        // No Android 11+ o sistema abre a tela de permissão, onde se escolhe "Permitir o tempo todo".
                        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    },
                    onExport = {
                        scope.launch {
                            val share = vm.exportCsv(context)
                            context.startActivity(Intent.createChooser(share, "Exportar dados"))
                        }
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(vm: PhoneViewModel) {
    val preset by vm.preset.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Preset.entries.forEach { p ->
            FilterChip(
                selected = preset == p,
                onClick = { if (p == Preset.CUSTOM) picking = true else vm.choose(p) },
                label = { Text(p.label) },
            )
        }
    }
    Text(
        if (period.hourly) period.firstDay.format(dayFmt)
        else "${period.firstDay.format(dayFmt)} a ${period.lastDay.format(dayFmt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        // O seletor devolve meia-noite UTC do dia escolhido.
                        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                        val s = day(state.selectedStartDateMillis!!)
                        val e = state.selectedEndDateMillis?.let(::day) ?: s
                        vm.custom(s, e)
                        picking = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancelar") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(500.dp), title = {
                Text("Escolha o período", Modifier.padding(start = 24.dp, top = 16.dp))
            })
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, note: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatGrid(cells: List<Triple<String, String, String?>>) {
    cells.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (t, v, n) -> StatCard(t, v, Modifier.weight(1f), n) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
        items.forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(10.dp).background(c, CircleShape))
                Spacer(Modifier.size(6.dp))
                Text(t, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun SunTab(vm: PhoneViewModel) {
    val ui by vm.sun.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    var selected by remember(period) { mutableStateOf<Int?>(null) }
    val u = ui ?: return
    val s = u.summary
    val med = net.extrasensors.shared.SkinType.II.medSed
    StatGrid(
        listOf(
            Triple("Tempo ao sol", duration(s.totalMs), null),
            Triple("Média por dia", duration(s.averagePerDayMs), null),
            Triple("Dose de UV", "${num(s.doseSed, 1)} SED", "pele tipo II avermelha com ~${num(med, 1)} SED"),
            Triple("Protetor aplicado", "${s.sunscreenCount}×", if (s.highUvDays > 0) "${s.highUvDays} dia(s) no sol com UV 8+" else null),
        )
    )
    ChartCard(if (period.hourly) "Minutos ao sol por hora e índice UV previsto" else "Minutos ao sol por dia e UV máximo previsto do dia") {
        if (!s.hasData) {
            Empty("Sem dados de sol neste período. Os dados chegam do relógio quando ele está conectado por Bluetooth.")
        } else {
            SunChart(u.points, period.hourly, vm.zone, selected) { selected = it }
            Legend(listOf(ChartColors.sun() to "Minutos ao sol", ChartColors.uv() to if (period.hourly) "UV previsto" else "UV máx. previsto"))
            selected?.let { i ->
                val p = u.points[i]
                val t = Instant.ofEpochMilli(p.start).atZone(vm.zone)
                val label = if (period.hourly) "${t.hour}h" else t.format(DateTimeFormatter.ofPattern("dd/MM"))
                // Distingue o UV enquanto você estava no sol (pela dose) da previsão do dia/hora.
                val exposed = p.exposureUv?.let { "UV no sol ~${num(it, 1)} (${UvMath.category(it)})" }
                val forecast = p.uv?.let {
                    (if (period.hourly) "UV previsto ${num(it, 1)}" else "máx. previsto do dia ${num(it, 1)}")
                }
                val parts = listOfNotNull("${duration(p.exposedMs)} ao sol", "${num(p.doseSed, 2)} SED", exposed, forecast)
                Text("$label: " + parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    Text(
        "Referências: a OMS recomenda proteção a partir do UV 3 e evitar o sol do meio do dia com UV 8 ou mais. " +
            "Reaplique o protetor a cada 2 h e depois de suar ou nadar (AAD/FDA). Doses são estimativas.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SkinTab(vm: PhoneViewModel) {
    val ui by vm.skin.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    var selected by remember(period) { mutableStateOf<Int?>(null) }
    val u = ui ?: return
    val s = u.summary
    StatGrid(
        listOf(
            Triple("Média", s?.let { "${num(it.mean, 1)} °C" } ?: "—", null),
            Triple("Leituras", s?.count?.toString() ?: "0", null),
            Triple("Mínima", s?.let { "${num(it.min, 1)} °C" } ?: "—", null),
            Triple("Máxima", s?.let { "${num(it.max, 1)} °C" } ?: "—", null),
        )
    )
    ChartCard(if (period.hourly) "Leituras de hoje" else "Média diária (faixa: mínima a máxima)") {
        if (u.points.isEmpty()) {
            Empty("Sem leituras de temperatura da pele neste período.")
        } else {
            SkinChart(u.points, period.hourly, period.from(vm.zone), period.to(vm.zone), vm.zone, s?.mean, selected) { selected = it }
            Legend(listOf(ChartColors.skin() to "Temperatura da pele (tracejado: média do período)"))
            selected?.let { i ->
                val p = u.points[i]
                val t = Instant.ofEpochMilli(p.start).atZone(vm.zone)
                val text = if (period.hourly) "${t.format(DateTimeFormatter.ofPattern("HH:mm"))}: ${num(p.mean, 1)} °C"
                else "${t.format(DateTimeFormatter.ofPattern("dd/MM"))}: média ${num(p.mean, 1)} °C " +
                    "(${num(p.min, 1)} a ${num(p.max, 1)}, ${p.count} leituras)"
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    Text(
        "Temperatura da pele do pulso, não temperatura corporal. Compare com a sua própria média.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusCard(vm: PhoneViewModel, onUv: () -> Unit, onExport: () -> Unit, onBackgroundLocation: () -> Unit) {
    val st by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s = st
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sincronização", style = MaterialTheme.typography.titleSmall)
            Text(
                if (s?.watchName != null) "Relógio conectado: ${s.watchName}" else "Relógio não conectado agora",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Última sincronização: ${whenText(s?.lastSyncAt ?: 0, vm.zone)}", style = MaterialTheme.typography.bodyMedium)
            Text("Índice UV atualizado: ${whenText(s?.uvFetchedAt ?: 0, vm.zone)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                s?.location?.let {
                    "Local (aprox.): ${num(it.first, 2)}, ${num(it.second, 2)} · obtido ${whenText(s.locationAt, vm.zone)}"
                } ?: "Local: sem permissão de localização (necessária para o UV)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s?.uvError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (s != null && s.location != null && !s.backgroundLocation) {
                Text(
                    "O local só é atualizado com este app aberto. Em viagem, o UV pode ser de outra cidade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBackgroundLocation) { Text("Atualizar o local sozinho (permitir o tempo todo)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = vm::syncNow, enabled = !busy) { Text("Sincronizar agora") }
                OutlinedButton(onClick = onUv, enabled = !busy) { Text("Atualizar UV") }
            }
            OutlinedButton(onClick = onExport) { Text("Exportar CSV") }
        }
    }
}
