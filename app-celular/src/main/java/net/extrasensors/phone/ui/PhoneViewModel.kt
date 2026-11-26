package net.extrasensors.phone.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrasensors.phone.PhoneGraph
import net.extrasensors.phone.stats.Period
import net.extrasensors.phone.stats.Preset
import net.extrasensors.phone.stats.SkinPoint
import net.extrasensors.phone.stats.SkinSummary
import net.extrasensors.phone.stats.Stats
import net.extrasensors.phone.stats.SunPoint
import net.extrasensors.phone.stats.SunSummary
import net.extrasensors.phone.sync.PhoneSync
import net.extrasensors.phone.uv.LocationHelper
import net.extrasensors.phone.uv.UvRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SunUi(val points: List<SunPoint>, val summary: SunSummary)
data class SkinUi(val points: List<SkinPoint>, val summary: SkinSummary?)
data class StatusUi(
    val watchName: String?,
    val lastSyncAt: Long,
    val uvFetchedAt: Long,
    val uvError: String?,
    val location: Pair<Double, Double>?,
    val locationAt: Long,
    val backgroundLocation: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewModel(private val g: PhoneGraph) : ViewModel() {
    val zone: ZoneId = ZoneId.systemDefault()
    private fun today() = LocalDate.now(zone)

    val preset = MutableStateFlow(Preset.WEEK)
    val period = MutableStateFlow(Period.of(Preset.WEEK, today()))
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun choose(p: Preset) {
        preset.value = p
        if (p != Preset.CUSTOM) period.value = Period.of(p, today())
    }

    fun custom(first: LocalDate, last: LocalDate) {
        preset.value = Preset.CUSTOM
        period.value = Period(minOf(first, last), maxOf(first, last))
    }

    private val dao = g.db.dao()

    val sun: StateFlow<SunUi?> = period.flatMapLatest { p ->
        val from = p.from(zone)
        val to = p.to(zone)
        combine(
            dao.buckets(from, to),
            dao.days(p.firstDay.toEpochDay(), p.lastDay.toEpochDay()),
            dao.uv(from, to),
            dao.sunscreen(from, to),
        ) { b, d, uv, apps ->
            val points = Stats.sunSeries(p, b, d, uv, zone)
            SunUi(points, Stats.sunSummary(points, p, apps.size, today()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val skin: StateFlow<SkinUi?> = period.flatMapLatest { p ->
        dao.skin(p.from(zone), p.to(zone)).map { r -> SkinUi(Stats.skinSeries(p, r, zone), Stats.skinSummary(r)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val nodeTicker = flow {
        while (true) {
            emit(PhoneSync.connectedWatches(g.context).firstOrNull()?.displayName)
            delay(30_000)
        }
    }

    val status: StateFlow<StatusUi?> = combine(g.prefs.changes, nodeTicker) { _, node ->
        StatusUi(
            node, g.prefs.lastSyncAt, g.prefs.uvFetchedAt, g.prefs.uvError, g.prefs.location,
            g.prefs.locationAt, LocationHelper.backgroundPermitted(g.context),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ao abrir o app: processa pendências, pede dados ao relógio e atualiza o UV se preciso. */
    fun onOpen() {
        viewModelScope.launch {
            PhoneSync.processExisting(g.context)
            runCatching { PhoneSync.requestSync(g.context) }
            if (LocationHelper.permitted(g.context)) {
                LocationHelper.refresh(g.context)
                if (System.currentTimeMillis() - g.prefs.uvFetchedAt > 60 * 60_000L) UvRepository.update(g.context)
            }
            if (period.value.lastDay != today() && preset.value != Preset.CUSTOM) choose(preset.value)
        }
    }

    fun syncNow() = action {
        PhoneSync.processExisting(g.context)
        val n = runCatching { PhoneSync.requestSync(g.context) }.getOrDefault(0)
        if (n == 0) "Relógio não conectado. Os dados chegam sozinhos quando ele se conectar."
        else "Pedido enviado ao relógio."
    }

    fun updateUv() = action {
        LocationHelper.refresh(g.context)
        UvRepository.update(g.context) ?: "Índice UV atualizado e enviado ao relógio."
    }

    private fun action(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            message.value = try { block() } finally { busy.value = false }
        }
    }

    /** Gera os CSVs (todo o histórico) e devolve o Intent de compartilhamento. */
    suspend fun exportCsv(context: Context): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        fun iso(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDateTime().toString()
        fun num(v: Double) = "%.3f".format(java.util.Locale.US, v)
        val files = listOf(
            File(dir, "sol_blocos_5min.csv").apply {
                writeText("inicio,minutos_ao_sol,dose_sed,uv_max\n" + dao.allBuckets().joinToString("") {
                    "${iso(it.bucketStart)},${num(it.exposedMs / 60_000.0)},${num(it.doseSed)},${it.maxUv?.let(::num) ?: ""}\n"
                })
            },
            File(dir, "sol_dias.csv").apply {
                writeText("dia,minutos_ao_sol\n" + dao.allDays().joinToString("") {
                    "${LocalDate.ofEpochDay(it.epochDay)},${num(it.totalMs / 60_000.0)}\n"
                })
            },
            File(dir, "protetor.csv").apply {
                writeText("aplicado_em\n" + dao.allSunscreen().joinToString("") { iso(it.timeMillis) + "\n" })
            },
            File(dir, "pele.csv").apply {
                writeText("horario,celsius\n" + dao.allSkin().joinToString("") { "${iso(it.timeMillis)},${num(it.celsius)}\n" })
            },
            File(dir, "uv_previsao.csv").apply {
                writeText("hora,indice_uv\n" + dao.uvSince(0).joinToString("") { "${iso(it.hourStart)},${num(it.uvi)}\n" })
            },
        )
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.export", it) })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
