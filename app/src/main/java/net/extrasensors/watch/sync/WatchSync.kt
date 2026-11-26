package net.extrasensors.watch.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import net.extrasensors.shared.EdaPoint
import net.extrasensors.shared.EdaSession
import net.extrasensors.shared.HrvMeasurement
import net.extrasensors.shared.SkinReading
import net.extrasensors.shared.SunBucket
import net.extrasensors.shared.SunDayTotal
import net.extrasensors.shared.SunscreenApplication
import net.extrasensors.shared.SyncAck
import net.extrasensors.shared.SyncCodec
import net.extrasensors.shared.SyncPaths
import net.extrasensors.shared.SyncPayload
import net.extrasensors.shared.UvHour
import net.extrasensors.shared.UvMath
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.data.UvHourEntity
import net.extrasensors.watch.graph

/**
 * Sincronização do relógio com o app do celular pelo Wearable Data Layer (Bluetooth).
 * O relógio não acessa a internet: só troca dados com o celular pareado.
 */
object WatchSync {
    private const val TAG = "WatchSync"
    private const val MAX_ROWS = 20_000
    private const val PUSH_INTERVAL_MS = 15 * 60_000L
    private const val UV_REQUEST_INTERVAL_MS = 60 * 60_000L
    private val mutex = Mutex()

    /** Lê o recibo do celular (maiores valores já gravados lá). */
    private suspend fun readAck(context: Context): SyncAck {
        val buffer = Wearable.getDataClient(context).dataItems.await()
        var ack = SyncAck()
        try {
            for (item in buffer) {
                if (item.uri.path != SyncPaths.ACK) continue
                val m = DataMapItem.fromDataItem(item).dataMap
                ack = ack.merge(
                    SyncAck(
                        m.getLong(SyncPaths.KEY_ACK_BUCKET),
                        m.getLong(SyncPaths.KEY_ACK_DAY),
                        m.getLong(SyncPaths.KEY_ACK_SKIN),
                        m.getLong(SyncPaths.KEY_ACK_APPLICATION),
                        m.getLong(SyncPaths.KEY_ACK_HRV),
                        m.getLong(SyncPaths.KEY_ACK_EDA_SESSION),
                        m.getLong(SyncPaths.KEY_ACK_EDA_POINT),
                    )
                )
            }
        } finally {
            buffer.release()
        }
        return ack
    }

    /**
     * Envia ao celular tudo o que mudou desde o último recibo. O Data Layer guarda o item e
     * entrega quando a conexão Bluetooth voltar. [force] ignora o intervalo mínimo de 15 min.
     */
    suspend fun push(context: Context, force: Boolean = false) = mutex.withLock {
        val g = context.graph
        val now = System.currentTimeMillis()
        if (!force && now - g.state.lastSyncPush() < PUSH_INTERVAL_MS) return@withLock
        try {
            val ack = readAck(context)
            val sun = g.db.sun()
            val payload = SyncPayload(
                buckets = sun.bucketsUpdatedAfter(ack.bucketUpdatedAt, MAX_ROWS)
                    .map { SunBucket(it.bucketStart, it.exposedMs, it.doseSed, it.maxUv, it.updatedAt) },
                days = sun.daysUpdatedAfter(ack.dayUpdatedAt, MAX_ROWS)
                    .map { SunDayTotal(it.epochDay, it.totalMs, it.updatedAt) },
                skin = g.db.skin().after(ack.skinTime, MAX_ROWS).map { SkinReading(it.timeMillis, it.celsius) },
                applications = sun.applicationsAfter(ack.applicationTime, MAX_ROWS).map { SunscreenApplication(it.timeMillis) },
                hrv = g.db.stress().hrvAfter(ack.hrvTime, MAX_ROWS)
                    .map { HrvMeasurement(it.timeMillis, it.rmssdMs, it.meanHr, it.beats, it.durationMs) },
                edaSessions = g.db.stress().sessionsUpdatedAfter(ack.edaSessionUpdatedAt, MAX_ROWS)
                    .map { EdaSession(it.startedAt, it.endedAt, it.source, it.updatedAt) },
                edaPoints = g.db.stress().pointsAfter(ack.edaPointTime, MAX_ROWS)
                    .map { EdaPoint(it.timeMillis, it.sessionStart, it.mean, it.min, it.max, it.peaks) },
            )
            g.state.setLastSyncPush(now)
            if (payload.isEmpty) return@withLock
            val request = PutDataMapRequest.create(SyncPaths.PENDING).apply {
                dataMap.putAsset(SyncPaths.KEY_PAYLOAD, Asset.createFromBytes(SyncCodec.encode(payload)))
                dataMap.putLong(SyncPaths.KEY_CREATED_AT, now)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao sincronizar", t)
        }
    }

    /** Índice UV agora, interpolado da previsão recebida do celular (null se não houver). */
    suspend fun currentUv(g: AppGraph, now: Long = System.currentTimeMillis()): Double? {
        val hours = g.db.uv().between(now - 3 * UvMath.HOUR_MS, now + 3 * UvMath.HOUR_MS)
            .map { UvHour(it.hourStart, it.uvi) }
        return UvMath.interpolate(hours, now)
    }

    /** Pede ao celular uma previsão nova quando a atual está velha (no máximo 1 pedido por hora). */
    suspend fun requestUvIfStale(context: Context) {
        val g = context.graph
        val now = System.currentTimeMillis()
        val last = g.db.uv().lastHour()
        // O celular envia 48 h à frente; menos de 40 h à frente indica previsão com mais de ~8 h.
        if (last != null && last > now + 40 * UvMath.HOUR_MS) return
        if (now - g.state.lastUvRequest() < UV_REQUEST_INTERVAL_MS) return
        g.state.setLastUvRequest(now)
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val mc = Wearable.getMessageClient(context)
            nodes.forEach { mc.sendMessage(it.id, SyncPaths.REQUEST_UV, ByteArray(0)).await() }
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao pedir UV", t)
        }
    }

    suspend fun storeUv(g: AppGraph, hours: LongArray, values: FloatArray) {
        val rows = hours.indices.map { UvHourEntity(hours[it], values[it].toDouble()) }
        g.db.uv().upsert(rows)
        g.db.uv().deleteBefore(System.currentTimeMillis() - 3 * 24 * UvMath.HOUR_MS)
    }
}

/** Recebe a previsão de UV e os pedidos de sincronização vindos do celular. */
class WatchSyncListener : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        val g = graph
        // O buffer é liberado ao retornar: copia os dados antes.
        val uv = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == SyncPaths.UV }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
            .map { it.getLongArray(SyncPaths.KEY_UV_HOURS) to it.getFloatArray(SyncPaths.KEY_UV_VALUES) }
        if (uv.isEmpty()) return
        runBlocking {
            uv.forEach { (h, v) -> if (h != null && v != null && h.size == v.size) WatchSync.storeUv(g, h, v) }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == SyncPaths.REQUEST_SYNC) {
            scope.launch { WatchSync.push(this@WatchSyncListener, force = true) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
