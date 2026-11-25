package net.extrasensors.watch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.extrasensors.shared.SkinType
import net.extrasensors.watch.domain.Barometry
import net.extrasensors.watch.domain.RainAlertState
import net.extrasensors.watch.domain.SunConfig
import net.extrasensors.watch.domain.SunState
import net.extrasensors.watch.domain.TrendThresholds

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.stateStore: DataStore<Preferences> by preferencesDataStore(name = "state")

/** Perfil exigido pelo tracker de perda de suor. Gênero conforme o SDK: 1 = masculino, 0 = feminino. */
data class UserProfile(val heightCm: Float, val weightKg: Float, val gender: Int, val age: Int)

data class AppSettings(
    val p0: Double = Barometry.STANDARD_P0,
    val rapidFall: Double = -4.0,
    val band: Double = 1.5,
    val rainAlert: Boolean = true,
    val luxThreshold: Float = 10_000f,
    val sunGoalMin: Int = 120,
    val sunAlert: Boolean = true,
    val dbOffset: Double = 90.0,
    val background: Boolean = true,
    val skinType: SkinType = SkinType.II,
    val uvAlerts: Boolean = true,
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val gender: Int? = null,
    val age: Int? = null,
) {
    val thresholds get() = TrendThresholds(rapidFall, band)
    val sunConfig get() = SunConfig(luxThreshold = luxThreshold, goalMs = sunGoalMin * 60_000L, skinType = skinType)
    val profile: UserProfile?
        get() = if (heightCm != null && weightKg != null && gender != null && age != null)
            UserProfile(heightCm, weightKg, gender, age) else null
}

class SettingsRepository(private val context: Context) {
    private object K {
        val p0 = doublePreferencesKey("p0")
        val rapidFall = doublePreferencesKey("rapid_fall")
        val band = doublePreferencesKey("band")
        val rainAlert = booleanPreferencesKey("rain_alert")
        val lux = floatPreferencesKey("lux_threshold")
        val goal = intPreferencesKey("sun_goal_min")
        val sunAlert = booleanPreferencesKey("sun_alert")
        val dbOffset = doublePreferencesKey("db_offset")
        val background = booleanPreferencesKey("background")
        val skinType = intPreferencesKey("skin_type")
        val uvAlerts = booleanPreferencesKey("uv_alerts")
        val height = floatPreferencesKey("height_cm")
        val weight = floatPreferencesKey("weight_kg")
        val gender = intPreferencesKey("gender")
        val age = intPreferencesKey("age")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            p0 = p[K.p0] ?: d.p0,
            rapidFall = p[K.rapidFall] ?: d.rapidFall,
            band = p[K.band] ?: d.band,
            rainAlert = p[K.rainAlert] ?: d.rainAlert,
            luxThreshold = p[K.lux] ?: d.luxThreshold,
            sunGoalMin = p[K.goal] ?: d.sunGoalMin,
            sunAlert = p[K.sunAlert] ?: d.sunAlert,
            dbOffset = p[K.dbOffset] ?: d.dbOffset,
            background = p[K.background] ?: d.background,
            skinType = p[K.skinType]?.let { SkinType.entries.getOrNull(it) } ?: d.skinType,
            uvAlerts = p[K.uvAlerts] ?: d.uvAlerts,
            heightCm = p[K.height],
            weightKg = p[K.weight],
            gender = p[K.gender],
            age = p[K.age],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setP0(v: Double) = context.settingsStore.edit { it[K.p0] = v }
    suspend fun setRapidFall(v: Double) = context.settingsStore.edit { it[K.rapidFall] = v }
    suspend fun setBand(v: Double) = context.settingsStore.edit { it[K.band] = v }
    suspend fun setRainAlert(v: Boolean) = context.settingsStore.edit { it[K.rainAlert] = v }
    suspend fun setLuxThreshold(v: Float) = context.settingsStore.edit { it[K.lux] = v }
    suspend fun setSunGoalMin(v: Int) = context.settingsStore.edit { it[K.goal] = v }
    suspend fun setSunAlert(v: Boolean) = context.settingsStore.edit { it[K.sunAlert] = v }
    suspend fun setDbOffset(v: Double) = context.settingsStore.edit { it[K.dbOffset] = v }
    suspend fun setBackground(v: Boolean) = context.settingsStore.edit { it[K.background] = v }
    suspend fun setSkinType(v: SkinType) = context.settingsStore.edit { it[K.skinType] = v.ordinal }
    suspend fun setUvAlerts(v: Boolean) = context.settingsStore.edit { it[K.uvAlerts] = v }
    suspend fun setHeight(v: Float) = context.settingsStore.edit { it[K.height] = v }
    suspend fun setWeight(v: Float) = context.settingsStore.edit { it[K.weight] = v }
    suspend fun setGender(v: Int) = context.settingsStore.edit { it[K.gender] = v }
    suspend fun setAge(v: Int) = context.settingsStore.edit { it[K.age] = v }
}

data class Diagnostics(
    val serviceStartAt: Long?,
    val alarmAt: Long?,
    val sampleAt: Long?,
    val sampleError: String?,
    val syncPushAt: Long?,
)

/** Estado interno persistido: exposição solar, alerta de chuva, último dB medido. */
class StateRepository(private val context: Context) {
    private object K {
        val sunDay = longPreferencesKey("sun_day")
        val sunToday = longPreferencesKey("sun_today_ms")
        val sunSince = longPreferencesKey("sun_since_ms")
        val sunLastTick = longPreferencesKey("sun_last_tick")
        val sunGoalNotified = booleanPreferencesKey("sun_goal_notified")
        val sunLastApp = longPreferencesKey("sun_last_application")
        val sunDose = doublePreferencesKey("sun_dose_today")
        val sunDoseAlertDay = longPreferencesKey("sun_dose_alert_day")
        val sunUvAlertAt = longPreferencesKey("sun_uv_alert_at")
        val lastSyncPush = longPreferencesKey("last_sync_push")
        val diagAlarmAt = longPreferencesKey("diag_alarm_at")
        val diagSampleAt = longPreferencesKey("diag_sample_at")
        val diagSampleError = stringPreferencesKey("diag_sample_error")
        val diagServiceStartAt = longPreferencesKey("diag_service_start_at")
        val lastUvRequest = longPreferencesKey("last_uv_request")
        val rainArmed = booleanPreferencesKey("rain_armed")
        val rainLast = longPreferencesKey("rain_last_alert")
        val lastDb = doublePreferencesKey("last_db")
        val lastDbAt = longPreferencesKey("last_db_at")
        val activeClimb = longPreferencesKey("active_climb_session")
    }

    private fun readSun(p: Preferences): SunState? {
        val day = p[K.sunDay] ?: return null
        return SunState(
            epochDay = day,
            todayMs = p[K.sunToday] ?: 0,
            sinceApplicationMs = p[K.sunSince] ?: 0,
            lastTickAt = p[K.sunLastTick],
            goalNotified = p[K.sunGoalNotified] ?: false,
            lastApplicationAt = p[K.sunLastApp],
            doseTodaySed = p[K.sunDose] ?: 0.0,
            doseAlertDay = p[K.sunDoseAlertDay],
            lastUvAlertAt = p[K.sunUvAlertAt],
        )
    }

    val sun: Flow<SunState?> = context.stateStore.data.map { readSun(it) }

    /** Atualização atômica do estado solar. Retorna o estado gravado. */
    suspend fun updateSun(initial: () -> SunState, transform: (SunState) -> SunState): SunState {
        var out: SunState? = null
        context.stateStore.edit { p ->
            val s = transform(readSun(p) ?: initial())
            p[K.sunDay] = s.epochDay
            p[K.sunToday] = s.todayMs
            p[K.sunSince] = s.sinceApplicationMs
            if (s.lastTickAt != null) p[K.sunLastTick] = s.lastTickAt else p.remove(K.sunLastTick)
            p[K.sunGoalNotified] = s.goalNotified
            if (s.lastApplicationAt != null) p[K.sunLastApp] = s.lastApplicationAt else p.remove(K.sunLastApp)
            p[K.sunDose] = s.doseTodaySed
            if (s.doseAlertDay != null) p[K.sunDoseAlertDay] = s.doseAlertDay else p.remove(K.sunDoseAlertDay)
            if (s.lastUvAlertAt != null) p[K.sunUvAlertAt] = s.lastUvAlertAt else p.remove(K.sunUvAlertAt)
            out = s
        }
        return out!!
    }

    suspend fun rainAlert(): RainAlertState {
        val p = context.stateStore.data.first()
        return RainAlertState(armed = p[K.rainArmed] ?: true, lastAlertAt = p[K.rainLast])
    }

    suspend fun setRainAlert(s: RainAlertState) = context.stateStore.edit { p ->
        p[K.rainArmed] = s.armed
        if (s.lastAlertAt != null) p[K.rainLast] = s.lastAlertAt else p.remove(K.rainLast)
    }

    val lastDb: Flow<Pair<Double, Long>?> = context.stateStore.data.map { p ->
        val v = p[K.lastDb] ?: return@map null
        v to (p[K.lastDbAt] ?: 0L)
    }

    suspend fun setLastDb(db: Double, at: Long) = context.stateStore.edit {
        it[K.lastDb] = db
        it[K.lastDbAt] = at
    }

    /** Diagnóstico do serviço, mostrado em Ajustes › Diagnóstico. */
    val diagnostics: Flow<Diagnostics> = context.stateStore.data.map { p ->
        Diagnostics(
            serviceStartAt = p[K.diagServiceStartAt],
            alarmAt = p[K.diagAlarmAt],
            sampleAt = p[K.diagSampleAt],
            sampleError = p[K.diagSampleError],
            syncPushAt = p[K.lastSyncPush],
        )
    }

    suspend fun setDiagServiceStart(t: Long) = context.stateStore.edit { it[K.diagServiceStartAt] = t }
    suspend fun setDiagAlarm(t: Long) = context.stateStore.edit { it[K.diagAlarmAt] = t }
    suspend fun setDiagSample(t: Long?, error: String?) = context.stateStore.edit {
        if (t != null) it[K.diagSampleAt] = t
        if (error != null) it[K.diagSampleError] = error else it.remove(K.diagSampleError)
    }

    suspend fun lastSyncPush(): Long = context.stateStore.data.first()[K.lastSyncPush] ?: 0L
    suspend fun setLastSyncPush(t: Long) = context.stateStore.edit { it[K.lastSyncPush] = t }
    suspend fun lastUvRequest(): Long = context.stateStore.data.first()[K.lastUvRequest] ?: 0L
    suspend fun setLastUvRequest(t: Long) = context.stateStore.edit { it[K.lastUvRequest] = t }

    val activeClimbSession: Flow<Long?> = context.stateStore.data.map { it[K.activeClimb] }

    suspend fun setActiveClimbSession(id: Long?) = context.stateStore.edit {
        if (id != null) it[K.activeClimb] = id else it.remove(K.activeClimb)
    }
}
