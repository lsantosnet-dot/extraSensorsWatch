package net.extrasensors.watch.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** Progresso e resultado da busca de altitude pelo GPS do relógio (sem internet). */
sealed interface GpsFix {
    /** Ainda procurando; [verticalAccuracy] é a melhor precisão vertical até agora (m). */
    data class Searching(val verticalAccuracy: Double?) : GpsFix
    /** Altitude acima do nível do mar (m) e precisão vertical (m). */
    data class Done(val altitudeMsl: Double, val verticalAccuracy: Double) : GpsFix
    data class Failure(val message: String) : GpsFix
}

object GpsAltitude {
    const val GOOD_ACCURACY_M = 10.0

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun available(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    /**
     * Liga o GPS e emite o progresso. Termina com [GpsFix.Done] assim que houver uma leitura com
     * precisão vertical de até 10 m. A altura do GPS é medida até o elipsoide (WGS84); ela é
     * convertida para o nível do mar com o modelo offline do Android (AltitudeConverter, API 34+).
     * O tempo limite fica a cargo de quem coleta.
     */
    @SuppressLint("MissingPermission")
    fun fixes(context: Context): Flow<GpsFix> = callbackFlow {
        if (!permitted(context)) {
            trySend(GpsFix.Failure("Permissão de localização precisa negada.")); close(); return@callbackFlow
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            trySend(GpsFix.Failure("GPS desligado: ative a localização no relógio.")); close(); return@callbackFlow
        }
        var best: Double? = null
        val converter = AltitudeConverter()
        val listener = LocationListener { loc: Location ->
            if (!loc.hasAltitude() || !loc.hasVerticalAccuracy()) return@LocationListener
            val acc = loc.verticalAccuracyMeters.toDouble()
            best = minOf(best ?: acc, acc)
            if (acc > GOOD_ACCURACY_M) {
                trySend(GpsFix.Searching(best))
                return@LocationListener
            }
            // O modelo do geoide é lido do disco: a conversão roda fora da thread principal.
            launch(Dispatchers.IO) {
                val msl = runCatching {
                    converter.addMslAltitudeToLocation(context, loc)
                    if (loc.hasMslAltitude()) loc.mslAltitudeMeters else null
                }.getOrNull()
                if (msl == null) {
                    trySend(GpsFix.Failure("Não foi possível converter a altitude do GPS para o nível do mar."))
                } else {
                    val mslAcc = if (loc.hasMslAltitudeAccuracy()) loc.mslAltitudeAccuracyMeters.toDouble() else 0.0
                    trySend(GpsFix.Done(msl, maxOf(acc, mslAcc)))
                }
                close()
            }
        }
        trySend(GpsFix.Searching(null))
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }
}
