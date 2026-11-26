package net.extrasensors.watch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.ClimbLive
import net.extrasensors.watch.Permissions
import net.extrasensors.watch.SweatPhase
import net.extrasensors.watch.data.AppSettings
import net.extrasensors.watch.data.ClimbPointEntity
import net.extrasensors.watch.data.ClimbSessionEntity
import net.extrasensors.watch.data.PressureSampleEntity
import net.extrasensors.watch.data.SkinTempEntity
import net.extrasensors.watch.data.SunBucketEntity
import net.extrasensors.watch.data.SunDayEntity
import net.extrasensors.watch.domain.SunTickResult
import net.extrasensors.watch.sync.WatchSync
import net.extrasensors.watch.HrvPhase
import net.extrasensors.watch.EdaLive
import net.extrasensors.watch.GpsPhase
import net.extrasensors.watch.data.HrvEntity
import net.extrasensors.watch.data.EdaSessionEntity
import net.extrasensors.watch.data.EdaPointEntity
import net.extrasensors.watch.domain.Hrv
import net.extrasensors.watch.domain.EdaAggregator
import net.extrasensors.watch.sensor.HeartSample
import net.extrasensors.watch.sensor.EdaSample
import net.extrasensors.watch.sensor.GpsAltitude
import net.extrasensors.watch.sensor.GpsFix
import net.extrasensors.watch.data.SweatResultEntity
import net.extrasensors.watch.domain.Backoff
import net.extrasensors.watch.domain.Barometry
import net.extrasensors.watch.domain.ClimbAccumulator
import net.extrasensors.watch.domain.ExponentialFilter
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.PressurePoint
import net.extrasensors.watch.domain.RainAlert
import net.extrasensors.watch.domain.RainTrend
import net.extrasensors.watch.domain.SkinSchedule
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.graph
import net.extrasensors.watch.sensor.SkinMeasurement
import net.extrasensors.watch.sensor.SkinMode
import net.extrasensors.watch.sensor.SweatOutcome
import net.extrasensors.watch.sensor.SweatSession
import java.time.ZoneId

object ServiceController {
    fun send(context: Context, action: String) {
        val i = Intent(context, SensorService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, i)
    }

    /** Inicia o serviço se a medição em segundo plano estiver ligada e houver permissão de notificação. */
    suspend fun startIfEnabled(context: Context) {
        val s = context.graph.settings.current()
        val activeClimb = context.graph.state.activeClimbSession.first() != null
        if ((s.background || activeClimb) && Permissions.notificationsGranted(context)) {
            runCatching { send(context, SensorService.ACTION_START) }
                .onFailure { Log.w("SensorService", "Não foi possível iniciar o serviço", it) }
        }
    }

    fun stopBackground(context: Context) {
        runCatching { send(context, SensorService.ACTION_STOP_IF_IDLE) }
    }
}

/**
 * Serviço em primeiro plano (tipo "health") que concentra toda a medição fora da tela:
 * amostras de pressão a cada 5 min, exposição solar (tick de 30 s), detecção de pulso,
 * sessão de subida, temperatura da pele periódica e corrida de perda de suor.
 */
class SensorService : LifecycleService() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP_IF_IDLE = "stop_if_idle"
        const val ACTION_CLIMB_START = "climb_start"
        const val ACTION_CLIMB_STOP = "climb_stop"
        const val ACTION_SWEAT_START = "sweat_start"
        const val ACTION_SWEAT_FINISH = "sweat_finish"
        const val ACTION_SWEAT_RESET = "sweat_reset"
        const val ACTION_SAMPLE_NOW = "sample_now"
        const val ACTION_HRV_MEASURE = "hrv_measure"
        const val ACTION_HRV_RESET = "hrv_reset"
        const val ACTION_EDA_START = "eda_start"
        const val ACTION_EDA_STOP = "eda_stop"
        const val ACTION_GPS_CALIBRATE = "gps_calibrate"
        private const val HRV_DURATION_MS = 60_000L
        private const val GPS_TIMEOUT_MS = 120_000L

        private const val TAG = "SensorService"
        private const val TICK_MS = 30_000L
        private const val PRESSURE_PERIOD_MS = 5 * 60_000L
        private const val PRESSURE_KEEP_MS = 48 * 60 * 60_000L
        private const val SKIN_PERIOD_MS = SkinSchedule.PERIOD_MS
        private const val SKIN_POLL_MS = 60_000L
        private const val SKIN_RETRY_MIN_MS = 30_000L
        private const val SKIN_RETRY_MAX_MS = 30 * 60_000L
        private const val BUCKET_MS = 5 * 60_000L
        // Renovado durante a sessão; o timeout só age se o app travar.
        private const val SESSION_LOCK_MS = 30 * 60_000L

        @Volatile
        var instance: SensorService? = null
            private set
    }

    private lateinit var g: AppGraph
    private val zone: ZoneId get() = ZoneId.systemDefault()
    @Volatile private var settings = AppSettings()
    @Volatile private var lastLux: Float? = null
    @Volatile private var onWrist: Boolean = true
    private val tickMutex = Mutex()
    private var lastNotifiedText: String? = null

    private lateinit var sunLock: PowerManager.WakeLock
    private lateinit var sessionLock: PowerManager.WakeLock

    private var climbJob: Job? = null
    private var hrvJob: Job? = null
    private var edaJob: Job? = null
    private var gpsJob: Job? = null
    private var edaAggregator: EdaAggregator? = null
    private var sweatJob: Job? = null
    private var sweatSession: SweatSession? = null
    private var backgroundStarted = false

    override fun onCreate() {
        super.onCreate()
        g = graph
        instance = this
        val pm = getSystemService(PowerManager::class.java)
        sunLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "extrasensors:sun").apply { setReferenceCounted(false) }
        sessionLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "extrasensors:session").apply { setReferenceCounted(false) }

        val ok = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_SERVICE,
                Notifications.serviceNotification(this, "Medindo sensores", 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        }.onFailure { Log.e(TAG, "startForeground falhou", it) }.isSuccess
        if (!ok) {
            stopSelf()
            return
        }
        g.live.serviceRunning.value = true
        lifecycleScope.launch { g.state.setDiagServiceStart(System.currentTimeMillis()) }

        lifecycleScope.launch {
            g.settings.settings.collect { s ->
                settings = s
                HrvReminder.schedule(this@SensorService, s)
                if (s.background) startBackground()
                updateSunWakeLock()
            }
        }
        lifecycleScope.launch { resumeClimbIfActive() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CLIMB_START -> startClimb()
            ACTION_CLIMB_STOP -> lifecycleScope.launch { stopClimb() }
            ACTION_SWEAT_START -> startSweat()
            ACTION_SWEAT_FINISH -> finishSweat()
            ACTION_SWEAT_RESET -> resetSweat()
            ACTION_STOP_IF_IDLE -> lifecycleScope.launch { stopIfIdle() }
            ACTION_SAMPLE_NOW -> onAlarm()
            ACTION_HRV_MEASURE -> startHrv()
            ACTION_HRV_RESET -> if (g.live.hrv.value !is HrvPhase.Measuring) {
                g.live.hrv.value = HrvPhase.Idle
                lifecycleScope.launch { stopIfIdle() }
            }
            ACTION_EDA_START -> startEda(source = 0)
            ACTION_EDA_STOP -> lifecycleScope.launch { stopEda() }
            ACTION_GPS_CALIBRATE -> startGpsCalibration()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        g.live.serviceRunning.value = false
        g.live.onWrist.value = null
        cancelAlarm()
        sweatSession?.cancel()
        if (::sunLock.isInitialized && sunLock.isHeld) sunLock.release()
        if (::sessionLock.isInitialized && sessionLock.isHeld) sessionLock.release()
        super.onDestroy()
    }

    // ---------------- Medição de fundo ----------------

    private fun startBackground() {
        if (backgroundStarted) return
        backgroundStarted = true

        if (g.light.available) {
            lifecycleScope.launch {
                runCatching {
                    // Batching de até 20 s no hardware para poupar bateria.
                    g.light.readings(batchLatencyUs = 20_000_000).collect { r ->
                        // Ao cruzar o limiar, fecha o intervalo anterior com o lux antigo, para
                        // os até 30 s no escuro não contarem como sol (e vice-versa).
                        val crossed = isBright() != isBright(r.value)
                        if (crossed) tick()
                        lastLux = r.value
                        g.live.lastLux.value = r.value
                        updateSunWakeLock()
                    }
                }.onFailure { Log.w(TAG, "Sensor de luz", it) }
            }
        }
        if (g.offBody.available) {
            lifecycleScope.launch {
                runCatching {
                    g.offBody.onWrist().collect {
                        onWrist = it
                        g.live.onWrist.value = it
                        updateSunWakeLock()
                    }
                }.onFailure { Log.w(TAG, "Sensor de pulso", it) }
            }
        } else {
            g.live.onWrist.value = true
        }
        lifecycleScope.launch {
            while (isActive) {
                tick()
                // Redundância: se o alarme de 5 min atrasar (Doze), o próprio tick amostra.
                if (System.currentTimeMillis() - lastSampleAt > 6 * 60_000L) {
                    launch { runCatching { samplePressure() } }
                }
                delay(TICK_MS)
            }
        }
        lifecycleScope.launch { skinContinuousLoop() }
        onAlarm()
    }

    private fun isBright(lux: Float? = lastLux): Boolean = (lux ?: 0f) > settings.luxThreshold

    /** Mantém a CPU acordada só enquanto há luz forte no pulso, para os ticks de 30 s acontecerem. */
    private fun updateSunWakeLock() {
        // instance vira null no início do onDestroy: um tick cancelado ali não pode religar o wakelock.
        val want = instance === this && settings.background && isBright() && onWrist
        // Renovado a cada tick (e a cada evento de luz ou de pulso); o timeout garante a
        // liberação se algo travar. Sem a renovação no tick, sol forte e estável deixa o sensor
        // de luz (on-change) sem eventos por mais de 10 min e a CPU dorme no meio da exposição.
        if (want) sunLock.acquire(10 * 60_000L)
        if (!want && sunLock.isHeld) sunLock.release()
    }

    private suspend fun tick() {
        try {
            tickLocked()
        } finally {
            updateSunWakeLock()
        }
    }

    private suspend fun tickLocked() = tickMutex.withLock {
        if (!settings.background) return@withLock
        val now = System.currentTimeMillis()
        val cfg = settings.sunConfig
        val uvi = WatchSync.currentUv(g, now)
        var result: SunTickResult? = null
        val s = g.state.updateSun({ SunExposure.initial(now, zone) }) { st ->
            val r = SunExposure.onTick(st, now, lastLux, onWrist, cfg, zone, uvi)
            result = r
            r.state
        }
        val r = result ?: return@withLock
        val sun = g.db.sun()
        r.closedDay?.let {
            sun.upsertDay(SunDayEntity(it.epochDay, it.totalMs, updatedAt = now))
            sun.deleteDaysBefore(s.epochDay - 365)
            sun.deleteBucketsBefore(now - 365L * 24 * 3600_000)
            sun.deleteApplicationsBefore(now - 365L * 24 * 3600_000)
            // Relógio guarda 90 dias de EDA e 1 ano de HRV; o histórico completo fica no celular.
            g.db.stress().deletePointsBefore(now - 90L * 24 * 3600_000)
            g.db.stress().deleteSessionsBefore(now - 90L * 24 * 3600_000)
            g.db.stress().deleteHrvBefore(now - 365L * 24 * 3600_000)
        }
        sun.upsertDay(SunDayEntity(s.epochDay, s.todayMs, updatedAt = now))
        if (r.exposedMs > 0) {
            // Bloco de 5 min para os gráficos do celular.
            val start = now - now % BUCKET_MS
            val b = sun.bucket(start)
            val maxUv = listOfNotNull(b?.maxUv, uvi).maxOrNull()
            sun.upsertBucket(
                SunBucketEntity(
                    bucketStart = start,
                    exposedMs = (b?.exposedMs ?: 0) + r.exposedMs,
                    doseSed = (b?.doseSed ?: 0.0) + r.doseSed,
                    maxUv = maxUv,
                    updatedAt = now,
                )
            )
        }
        if (r.goalReached && settings.sunAlert) Notifications.sunAlert(this)
        if (r.doseAlert && settings.uvAlerts) Notifications.doseAlert(this, s.doseTodaySed, cfg.skinType.medSed)
        if (r.uvAlert && settings.uvAlerts && uvi != null) Notifications.uvAlert(this, uvi)
        if (r.closedDay != null) lifecycleScope.launch { WatchSync.push(this@SensorService, force = true) }
        refreshNotification(s.sinceApplicationMs)
    }

    private fun refreshNotification(sinceMs: Long? = null) {
        val climb = g.live.climb.value
        val sweat = g.live.sweat.value
        val (text, page) = when {
            sweat is SweatPhase.Running -> "Corrida em andamento" to 8
            climb != null -> "Subida ${Format.number(climb.gain)} m" to 3
            sinceMs != null -> "Sol ${Format.duration(sinceMs)} desde o protetor" to 5
            else -> (lastNotifiedText ?: "Medindo sensores") to 0
        }
        if (text != lastNotifiedText) {
            lastNotifiedText = text
            Notifications.updateService(this, text, page)
            Notifications.requestComplicationUpdate(this)
        }
    }

    // ---------------- Alarme de 5 min: pressão, chuva, pele ----------------

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1, Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // USE_EXACT_ALARM é concedida na instalação; canScheduleExactAlarms() é checado abaixo.
    @SuppressLint("MissingPermission")
    private fun scheduleAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + PRESSURE_PERIOD_MS
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
        }
    }

    private fun cancelAlarm() {
        runCatching { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
    }

    /** Chamado pelo [AlarmReceiver] (e na inicialização). */
    fun onAlarm() {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "extrasensors:alarm")
        lock.acquire(60_000)
        scheduleAlarm()
        lifecycleScope.launch {
            try {
                g.state.setDiagAlarm(System.currentTimeMillis())
                skinWake.trySend(Unit)
                if (settings.background) {
                    samplePressure()
                    tick()
                    periodicSkin()
                    WatchSync.requestUvIfStale(this@SensorService)
                    WatchSync.push(this@SensorService)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Falha no alarme", t)
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    private val sampleMutex = Mutex()
    @Volatile private var lastSampleAt = 0L

    /** Amostra de pressão; também chamada pelo tick, se o alarme atrasar mais de 6 min. */
    private suspend fun samplePressure() = sampleMutex.withLock {
        g.state.setDiagSample(null, null) // limpa erro antigo: o diagnóstico mostra só a tentativa atual
        if (!g.pressure.available) {
            g.state.setDiagSample(null, "Barômetro indisponível")
            return@withLock
        }
        val hPa = try {
            g.pressure.sampleMedian(10_000)
        } catch (t: Throwable) {
            g.state.setDiagSample(null, "Erro no barômetro: ${t.message}")
            return@withLock
        }
        if (hPa == null) {
            g.state.setDiagSample(null, "Barômetro não respondeu em 20 s")
            return@withLock
        }
        val now = System.currentTimeMillis()
        lastSampleAt = now
        g.state.setDiagSample(now, null)
        val dao = g.db.pressure()
        dao.insert(PressureSampleEntity(timeMillis = now, hPa = hPa))
        dao.deleteBefore(now - PRESSURE_KEEP_MS)

        val samples = dao.since(now - RainTrend.WINDOW_MS - 2 * RainTrend.TOLERANCE_MS)
            .map { PressurePoint(it.timeMillis, it.hPa) }
        val t = settings.thresholds
        val result = RainTrend.compute(samples, now, t)
        val (newState, fire) = RainAlert.evaluate(result, g.state.rainAlert(), now, t)
        g.state.setRainAlert(newState)
        if (fire && settings.rainAlert) Notifications.rainAlert(this)
    }

    // ---------------- Temperatura da pele ----------------

    private fun healthPermitted(): Boolean =
        Permissions.healthRequired(this).all { Permissions.granted(this, it) }

    /** Só um uso do sensor de pele por vez no serviço (contínuo ou sob demanda). */
    private val skinMutex = Mutex()
    @Volatile private var skinContinuousCollecting = false
    /** Acordado pelo alarme de 5 min: o delay das corrotinas não anda com a CPU dormindo. */
    private val skinWake = Channel<Unit>(Channel.CONFLATED)

    /** Espera até o instante [at] do relógio de parede, conferindo a cada minuto ou a cada alarme. */
    private suspend fun waitSkinUntil(at: Long) {
        while (true) {
            val left = at - System.currentTimeMillis()
            if (left <= 0) return
            withTimeoutOrNull(minOf(left, SKIN_POLL_MS)) { skinWake.receive() }
        }
    }

    /**
     * Leitura contínua da pele que não desiste: espera a permissão de saúde chegar (ela é pedida só
     * ao abrir a tela Pele, depois do serviço já ter iniciado) e, se o fluxo fechar por erro do
     * tracker ou queda da conexão, tenta de novo com espera crescente (30 s, 1 min, 2 min... até 30 min).
     * Em relógio só com medição sob demanda, sai e deixa o [periodicSkin] cuidar.
     */
    private suspend fun skinContinuousLoop() {
        val backoff = Backoff(SKIN_RETRY_MIN_MS, SKIN_RETRY_MAX_MS)
        while (currentCoroutineContext().isActive) {
            if (!healthPermitted()) {
                waitSkinUntil(System.currentTimeMillis() + SKIN_POLL_MS)
                continue
            }
            val mode = g.skin.mode().getOrElse {
                g.live.skinError.value = it.message
                null
            }
            when (mode) {
                SkinMode.ON_DEMAND, SkinMode.NONE -> return
                SkinMode.CONTINUOUS -> if (collectSkinContinuous()) backoff.reset()
                null -> Unit
            }
            waitSkinUntil(System.currentTimeMillis() + backoff.next())
        }
    }

    /** Coleta até o fluxo contínuo fechar. Retorna true se chegou ao menos uma leitura válida. */
    private suspend fun collectSkinContinuous(): Boolean = skinMutex.withLock {
        var lastSaved = g.db.skin().latest()?.timeMillis ?: 0L
        var gotSuccess = false
        var lastFailure: String? = null
        skinContinuousCollecting = true
        try {
            g.skin.continuous().collect { m ->
                when (m) {
                    is SkinMeasurement.Success -> {
                        gotSuccess = true
                        g.live.skinError.value = null
                        if (m.timeMillis - lastSaved >= SKIN_PERIOD_MS) {
                            g.db.skin().insert(SkinTempEntity(timeMillis = m.timeMillis, celsius = m.celsius))
                            g.db.skin().deleteBefore(m.timeMillis - 30L * 24 * 3600_000)
                            lastSaved = m.timeMillis
                        }
                    }
                    is SkinMeasurement.Failure -> lastFailure = m.message
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Pele contínua", t)
            lastFailure = t.message
        } finally {
            skinContinuousCollecting = false
        }
        // Mostra o motivo na tela Pele só quando a tentativa inteira falhou.
        if (!gotSuccess && lastFailure != null) g.live.skinError.value = lastFailure
        gotSuccess
    }

    /**
     * Uma medição sob demanda quando a última leitura gravada tem 30 min ou mais, com o relógio
     * no pulso. Vale também no modo contínuo enquanto a leitura contínua estiver parada,
     * esperando para tentar de novo.
     */
    private suspend fun periodicSkin() {
        if (!onWrist || !healthPermitted()) return
        val mode = g.skin.mode().getOrNull() ?: return
        val now = System.currentTimeMillis()
        val last = g.db.skin().latest()?.timeMillis
        if (!SkinSchedule.onDemandDue(mode != SkinMode.NONE, skinContinuousCollecting, last, now)) return
        if (!skinMutex.tryLock()) return
        try {
            val m = g.skin.measureOnce(30_000)
            if (m is SkinMeasurement.Success) {
                g.db.skin().insert(SkinTempEntity(timeMillis = m.timeMillis, celsius = m.celsius))
                g.db.skin().deleteBefore(now - 30L * 24 * 3600_000)
            }
        } finally {
            skinMutex.unlock()
        }
    }

    // ---------------- Subida acumulada ----------------

    private suspend fun resumeClimbIfActive() {
        val id = g.state.activeClimbSession.first() ?: return
        val s = g.db.climb().latestSession()
        if (s == null || s.id != id || s.endedAt != null) {
            g.state.setActiveClimbSession(null)
            return
        }
        runClimb(s)
    }

    private fun startClimb() {
        if (climbJob?.isActive == true || !g.pressure.available) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val id = g.db.climb().insertSession(ClimbSessionEntity(startedAt = now))
            g.state.setActiveClimbSession(id)
            g.db.climb().deletePointsExcept(id)
            runClimb(ClimbSessionEntity(id = id, startedAt = now))
            if (settings.gpsOnClimb && GpsAltitude.available(this@SensorService) && GpsAltitude.permitted(this@SensorService)) {
                startGpsCalibration()
            }
        }
    }

    private fun holdSessionLock() = sessionLock.acquire(SESSION_LOCK_MS)

    private fun runClimb(session: ClimbSessionEntity) {
        holdSessionLock()
        climbJob = lifecycleScope.launch {
            var acc = ClimbAccumulator(gain = session.gain, loss = session.loss)
            var minAlt = session.minAltitude
            var maxAlt = session.maxAltitude
            val filter = ExponentialFilter(0.2)
            var lastSaved = 0L
            var current = session
            var usedP0 = settings.p0
            g.live.climb.value = ClimbLive(session.id, session.startedAt, acc.gain, acc.loss, null)
            refreshNotification()
            runCatching {
                g.pressure.readings(1_000_000).collect { r ->
                    val p = filter.add(r.value.toDouble())
                    if (settings.p0 != usedP0) {
                        // Recalibração (GPS ou manual) no meio da sessão: o salto de altitude não é subida.
                        usedP0 = settings.p0
                        acc = acc.copy(reference = null)
                    }
                    val alt = Barometry.altitude(usedP0, p)
                    acc = acc.add(alt)
                    minAlt = minOf(minAlt ?: alt, alt)
                    maxAlt = maxOf(maxAlt ?: alt, alt)
                    g.live.climb.value = ClimbLive(session.id, session.startedAt, acc.gain, acc.loss, alt)
                    val now = System.currentTimeMillis()
                    if (now - lastSaved >= 10_000) {
                        lastSaved = now
                        holdSessionLock()
                        current = current.copy(gain = acc.gain, loss = acc.loss, minAltitude = minAlt, maxAltitude = maxAlt)
                        g.db.climb().upsertSession(current)
                        g.db.climb().insertPoint(ClimbPointEntity(sessionId = session.id, timeMillis = now, altitude = alt))
                        refreshNotification()
                    }
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "Subida", it) }
        }
    }

    private suspend fun stopClimb() {
        val live = g.live.climb.value
        climbJob?.cancel()
        climbJob = null
        val s = g.db.climb().latestSession()
        if (s != null && s.endedAt == null) {
            g.db.climb().upsertSession(
                s.copy(
                    endedAt = System.currentTimeMillis(),
                    gain = live?.gain ?: s.gain,
                    loss = live?.loss ?: s.loss,
                )
            )
        }
        g.state.setActiveClimbSession(null)
        g.live.climb.value = null
        releaseSessionLockIfIdle()
        refreshNotification(g.state.sun.first()?.sinceApplicationMs)
        stopIfIdle()
    }

    // ---------------- Perda de suor ----------------

    private fun startSweat() {
        if (sweatJob?.isActive == true) return
        g.live.sweat.value = SweatPhase.Starting
        sweatJob = lifecycleScope.launch {
            val profile = settings.profile
            if (profile == null) {
                g.live.sweat.value = SweatPhase.Error("Configure seu perfil em Ajustes.")
                return@launch
            }
            val session = g.sweat.start(profile).getOrElse {
                g.live.sweat.value = SweatPhase.Error(it.message ?: "Não foi possível iniciar.")
                return@launch
            }
            sweatSession = session
            holdSessionLock()
            val startedAt = System.currentTimeMillis()
            g.live.sweat.value = SweatPhase.Running(startedAt)
            refreshNotification()
            // Grava a EDA junto com a corrida, se o relógio tiver o sensor e a permissão.
            if (g.live.eda.value == null && edaPermitted() && g.eda.isSupported().getOrDefault(false)) startEda(source = 1)
            if (g.steps.available && Permissions.granted(this@SensorService, Manifest.permission.ACTIVITY_RECOGNITION)) {
                val window = ArrayDeque<Pair<Long, Float>>()
                var lastFeed = 0L
                runCatching {
                    g.steps.counts().collect { r ->
                        val now = System.currentTimeMillis()
                        window.addLast(now to r.value)
                        while (window.size > 1 && now - window.first().first > 60_000) window.removeFirst()
                        if (now - lastFeed >= 10_000 && window.size > 1) {
                            lastFeed = now
                            holdSessionLock()
                            val (t0, s0) = window.first()
                            val minutes = (now - t0) / 60_000.0
                            if (minutes > 0) session.feedStepsPerMinute(((r.value - s0) / minutes).toFloat(), now)
                        }
                    }
                }
            }
        }
    }

    private fun finishSweat() {
        val session = sweatSession ?: return
        val running = g.live.sweat.value as? SweatPhase.Running ?: return
        sweatJob?.cancel()
        sweatSession = null
        g.live.sweat.value = SweatPhase.Calculating
        lifecycleScope.launch {
            if (g.live.eda.value?.source == 1) stopEda()
            val outcome = session.finish()
            val now = System.currentTimeMillis()
            g.live.sweat.value = when (outcome) {
                is SweatOutcome.Success -> {
                    g.db.sweat().insert(SweatResultEntity(timeMillis = now, liters = outcome.liters, durationMs = now - running.startedAt))
                    SweatPhase.Done(outcome.liters)
                }
                is SweatOutcome.Failure -> SweatPhase.Error(outcome.message)
            }
            releaseSessionLockIfIdle()
            refreshNotification(g.state.sun.first()?.sinceApplicationMs)
            stopIfIdle()
        }
    }

    private fun resetSweat() {
        if (g.live.sweat.value is SweatPhase.Running || g.live.sweat.value is SweatPhase.Calculating) return
        g.live.sweat.value = SweatPhase.Idle
        lifecycleScope.launch { stopIfIdle() }
    }

    private fun otherSessionsBusy(): Boolean =
        g.live.hrv.value is HrvPhase.Measuring || g.live.eda.value != null || g.live.gps.value is GpsPhase.Searching

    private fun releaseSessionLockIfIdle() {
        val sweatBusy = g.live.sweat.value is SweatPhase.Running || g.live.sweat.value is SweatPhase.Calculating
        if (g.live.climb.value == null && !sweatBusy && !otherSessionsBusy() && sessionLock.isHeld) sessionLock.release()
    }

    /** Para o serviço quando a medição de fundo está desligada e não há sessão ativa. */
    private suspend fun stopIfIdle() {
        val s = g.settings.current()
        val sweatBusy = g.live.sweat.value is SweatPhase.Running || g.live.sweat.value is SweatPhase.Calculating ||
            g.live.sweat.value is SweatPhase.Starting
        if (!s.background && g.live.climb.value == null && !sweatBusy && !otherSessionsBusy()) {
            stopSelf()
        }
    }

    /** Interrompe uma coleta de fluxo sem cancelar a rotina que vem depois dela. */
    private object StopCollect : RuntimeException() {
        private fun readResolve(): Any = StopCollect
    }

    private suspend fun stoppable(block: suspend () -> Unit) {
        try { block() } catch (_: StopCollect) { }
    }

    // ---------------- HRV (1 min parado) ----------------

    private fun startHrv() {
        if (hrvJob?.isActive == true) return
        if (!healthPermitted()) {
            g.live.hrv.value = HrvPhase.Error("Permissão de sensores de saúde negada.")
            return
        }
        val startedAt = System.currentTimeMillis()
        g.live.hrv.value = HrvPhase.Measuring(startedAt, 0)
        holdSessionLock()
        hrvJob = lifecycleScope.launch {
            val ibis = mutableListOf<Int>()
            var firstAt: Long? = null
            var failure: String? = null
            // Até 75 s: espera o sensor estabilizar e grava 60 s a partir do primeiro intervalo válido.
            stoppable { withTimeoutOrNull(HRV_DURATION_MS + 15_000) {
                g.heart.samples().collect { smp ->
                    when (smp) {
                        is HeartSample.Failure -> { failure = smp.message; throw StopCollect }
                        is HeartSample.Ibis -> {
                            val now = System.currentTimeMillis()
                            if (firstAt == null) firstAt = now
                            ibis += smp.ibis
                            g.live.hrv.value = HrvPhase.Measuring(startedAt, ibis.size)
                            if (now - firstAt >= HRV_DURATION_MS) throw StopCollect
                        }
                    }
                }
            } }
            val result = Hrv.rmssd(ibis)
            g.live.hrv.value = when {
                failure != null -> HrvPhase.Error(failure)
                result == null -> HrvPhase.Error("Poucos batimentos válidos (${ibis.size}). Fique parado, com o relógio firme, e tente de novo.")
                else -> {
                    val end = System.currentTimeMillis()
                    g.db.stress().insertHrv(
                        HrvEntity(timeMillis = end, rmssdMs = result.rmssdMs, meanHr = result.meanHr, beats = result.beats, durationMs = end - startedAt)
                    )
                    Notifications.cancel(this@SensorService, Notifications.ID_HRV)
                    lifecycleScope.launch { WatchSync.push(this@SensorService, force = true) }
                    HrvPhase.Done(result.rmssdMs, result.meanHr, result.beats)
                }
            }
            releaseSessionLockIfIdle()
        }
    }

    // ---------------- EDA (sessões) ----------------

    private fun edaPermitted(): Boolean = Permissions.edaRequired(this).all { Permissions.granted(this, it) }

    private fun startEda(source: Int) {
        if (edaJob?.isActive == true) return
        if (!edaPermitted()) {
            g.live.edaError.value = "Permissão de sensores de saúde negada."
            return
        }
        val startedAt = System.currentTimeMillis()
        g.live.edaError.value = null
        g.live.eda.value = EdaLive(startedAt, source, null, 0.0)
        holdSessionLock()
        edaJob = lifecycleScope.launch {
            val dao = g.db.stress()
            dao.upsertSession(EdaSessionEntity(startedAt, null, source, startedAt))
            val agg = EdaAggregator()
            edaAggregator = agg
            var peaks = 0
            stoppable { g.eda.samples().collect { smp ->
                when (smp) {
                    is EdaSample.Failure -> {
                        g.live.edaError.value = smp.message
                        throw StopCollect
                    }
                    is EdaSample.Reading -> {
                        agg.add(smp.timeMillis, smp.microsiemens)?.let { w ->
                            peaks += w.peaks
                            dao.upsertPoint(EdaPointEntity(w.timeMillis, startedAt, w.mean, w.min, w.max, w.peaks))
                            holdSessionLock()
                        }
                        val minutes = (System.currentTimeMillis() - startedAt) / 60_000.0
                        g.live.eda.value = EdaLive(startedAt, source, smp.microsiemens, if (minutes > 0.5) peaks / minutes else 0.0)
                    }
                }
            } }
        }
        edaJob?.invokeOnCompletion {
            // Erro do sensor: fecha a sessão também.
            if (g.live.eda.value?.startedAt == startedAt && g.live.edaError.value != null) {
                lifecycleScope.launch { stopEda() }
            }
        }
    }

    private suspend fun stopEda() {
        val live = g.live.eda.value ?: return
        edaJob?.cancel()
        edaJob = null
        val now = System.currentTimeMillis()
        val dao = g.db.stress()
        edaAggregator?.flush()?.let { w ->
            dao.upsertPoint(EdaPointEntity(w.timeMillis, live.startedAt, w.mean, w.min, w.max, w.peaks))
        }
        edaAggregator = null
        dao.upsertSession(EdaSessionEntity(live.startedAt, now, live.source, now))
        g.live.eda.value = null
        WatchSync.push(this, force = true)
        releaseSessionLockIfIdle()
        stopIfIdle()
    }

    // ---------------- Calibração da altitude pelo GPS ----------------

    private fun startGpsCalibration() {
        if (gpsJob?.isActive == true) return
        if (!GpsAltitude.available(this)) {
            g.live.gps.value = GpsPhase.Error("Este relógio não tem GPS.")
            return
        }
        if (!GpsAltitude.permitted(this)) {
            g.live.gps.value = GpsPhase.Error("Permissão de localização precisa negada.")
            return
        }
        val startedAt = System.currentTimeMillis()
        g.live.gps.value = GpsPhase.Searching(null, startedAt)
        holdSessionLock()
        gpsJob = lifecycleScope.launch {
            var outcome: GpsPhase = GpsPhase.Error("Sem sinal de GPS bom em 2 min. Tente ao ar livre, com o céu aberto.")
            stoppable { withTimeoutOrNull(GPS_TIMEOUT_MS) {
                GpsAltitude.fixes(this@SensorService).collect { f ->
                    when (f) {
                        is GpsFix.Searching -> g.live.gps.value = GpsPhase.Searching(f.verticalAccuracy, startedAt)
                        is GpsFix.Failure -> { outcome = GpsPhase.Error(f.message); throw StopCollect }
                        is GpsFix.Done -> {
                            val p = g.pressure.sampleMedian(3_000)
                            outcome = if (p == null) {
                                GpsPhase.Error("Sem leitura do barômetro para calibrar.")
                            } else {
                                val p0 = Barometry.p0FromAltitude(p, f.altitudeMsl)
                                g.settings.setP0(p0)
                                g.state.setGpsCalibration(System.currentTimeMillis(), f.altitudeMsl, f.verticalAccuracy)
                                GpsPhase.Done(f.altitudeMsl, f.verticalAccuracy, p0)
                            }
                            throw StopCollect
                        }
                    }
                }
            } }
            g.live.gps.value = outcome
            releaseSessionLockIfIdle()
        }
    }
}
