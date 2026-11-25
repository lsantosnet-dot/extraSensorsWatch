package net.extrasensors.phone.uv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrasensors.phone.data.UvRow
import net.extrasensors.phone.phoneGraph
import net.extrasensors.phone.sync.PhoneSync
import net.extrasensors.shared.UvHour
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.round

/** Previsão horária do índice UV pelo Open-Meteo (gratuito, sem chave). */
object OpenMeteo {
    fun url(lat: Double, lon: Double): String =
        String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                "&hourly=uv_index&past_days=1&forecast_days=2&timeformat=unixtime&timezone=GMT",
            lat, lon,
        )

    /** Converte a resposta JSON em horas (epoch millis) e índices; ignora horas sem valor. */
    fun parse(json: String): List<UvHour> {
        val hourly = JSONObject(json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val values = hourly.getJSONArray("uv_index")
        return (0 until minOf(times.length(), values.length()))
            .filterNot { values.isNull(it) }
            .map { UvHour(times.getLong(it) * 1000, values.getDouble(it).coerceAtLeast(0.0)) }
    }

    suspend fun fetch(lat: Double, lon: Double): List<UvHour> = withContext(Dispatchers.IO) {
        val conn = URL(url(lat, lon)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "ExtraSensors-Android")
        try {
            val code = conn.responseCode
            if (code != 200) error("Open-Meteo respondeu HTTP $code")
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

object LocationHelper {
    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Arredonda para 2 casas (~1 km): é só o necessário para a previsão de UV. */
    fun rounded(lat: Double, lon: Double) = round(lat * 100) / 100 to round(lon * 100) / 100

    /** Localização aproximada atual (com o app aberto). Salva nas preferências. */
    @SuppressLint("MissingPermission")
    suspend fun refresh(context: Context): Pair<Double, Double>? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) || it == LocationManager.PASSIVE_PROVIDER }
        var loc: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                loc = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { cont ->
                        val signal = android.os.CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        lm.getCurrentLocation(p, signal, context.mainExecutor) { cont.resume(it) }
                    }
                }
                if (loc != null) break
            }
        }
        if (loc == null) loc = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val r = loc?.let { rounded(it.latitude, it.longitude) } ?: return null
        context.phoneGraph.prefs.location = r
        return r
    }
}

object UvRepository {
    private const val TAG = "UvRepository"

    /** Busca a previsão, guarda no banco e envia ao relógio. Retorna null se deu certo, ou o erro. */
    suspend fun update(context: Context): String? {
        val g = context.phoneGraph
        val loc = g.prefs.location ?: return "Sem localização: abra o app e permita a localização aproximada.".also {
            g.prefs.uvError = it
        }
        return try {
            val hours = OpenMeteo.fetch(loc.first, loc.second)
            if (hours.isEmpty()) error("Previsão vazia")
            g.db.dao().upsertUv(hours.map { UvRow(it.hourStart, it.uvi) })
            val now = System.currentTimeMillis()
            g.prefs.uvFetchedAt = now
            g.prefs.uvError = null
            runCatching { PhoneSync.pushUv(context, hours, now) }
                .onFailure { Log.w(TAG, "Relógio indisponível; o Data Layer entrega depois", it) }
            null
        } catch (t: Throwable) {
            val msg = "Falha ao buscar o índice UV: ${t.message}"
            g.prefs.uvError = msg
            msg
        }
    }
}

/** Atualiza a previsão de UV a cada hora, com internet. */
class UvWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (applicationContext.phoneGraph.prefs.location == null) return Result.success()
        return if (UvRepository.update(applicationContext) == null) Result.success() else Result.retry()
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UvWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("uv", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<UvWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("uv-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
