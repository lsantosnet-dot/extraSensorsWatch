package net.extrasensors.phone.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import net.extrasensors.phone.data.EdaPointRow
import net.extrasensors.phone.data.EdaSessionRow
import net.extrasensors.phone.data.HrvRow
import net.extrasensors.phone.data.SkinRow
import net.extrasensors.phone.data.SunBucketRow
import net.extrasensors.phone.data.SunDayRow
import net.extrasensors.phone.data.SunscreenRow
import net.extrasensors.phone.phoneGraph
import net.extrasensors.phone.uv.UvWorker
import net.extrasensors.shared.SyncAck
import net.extrasensors.shared.SyncCodec
import net.extrasensors.shared.SyncPaths
import net.extrasensors.shared.UvHour

/** Recebe os dados do relógio, grava no banco do celular e devolve o recibo. */
object PhoneSync {
    private const val TAG = "PhoneSync"
    private val mutex = Mutex()

    /** Processa um DataItem "/sync/pending". */
    suspend fun process(context: Context, item: DataItem) = mutex.withLock {
        val g = context.phoneGraph
        val map = DataMapItem.fromDataItem(item).dataMap
        val asset = map.getAsset(SyncPaths.KEY_PAYLOAD) ?: return@withLock
        val bytes = Wearable.getDataClient(context).getFdForAsset(asset).await()
            .inputStream.use { it.readBytes() }
        val p = SyncCodec.decode(bytes)
        val dao = g.db.dao()
        dao.upsertBuckets(p.buckets.map { SunBucketRow(it.bucketStart, it.exposedMs, it.doseSed, it.maxUv) })
        dao.upsertDays(p.days.map { SunDayRow(it.epochDay, it.totalMs) })
        dao.upsertSkin(p.skin.map { SkinRow(it.timeMillis, it.celsius) })
        dao.upsertSunscreen(p.applications.map { SunscreenRow(it.timeMillis) })
        dao.upsertHrv(p.hrv.map { HrvRow(it.timeMillis, it.rmssdMs, it.meanHr, it.beats, it.durationMs) })
        dao.upsertEdaSessions(p.edaSessions.map { EdaSessionRow(it.startedAt, it.endedAt, it.source) })
        dao.upsertEdaPoints(p.edaPoints.map { EdaPointRow(it.timeMillis, it.sessionStart, it.mean, it.min, it.max, it.peaks) })

        val ack = g.prefs.ack.merge(SyncAck.of(p))
        g.prefs.ack = ack
        g.prefs.lastSyncAt = System.currentTimeMillis()
        val req = PutDataMapRequest.create(SyncPaths.ACK).apply {
            dataMap.putLong(SyncPaths.KEY_ACK_BUCKET, ack.bucketUpdatedAt)
            dataMap.putLong(SyncPaths.KEY_ACK_DAY, ack.dayUpdatedAt)
            dataMap.putLong(SyncPaths.KEY_ACK_SKIN, ack.skinTime)
            dataMap.putLong(SyncPaths.KEY_ACK_APPLICATION, ack.applicationTime)
            dataMap.putLong(SyncPaths.KEY_ACK_HRV, ack.hrvTime)
            dataMap.putLong(SyncPaths.KEY_ACK_EDA_SESSION, ack.edaSessionUpdatedAt)
            dataMap.putLong(SyncPaths.KEY_ACK_EDA_POINT, ack.edaPointTime)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }

    /** Processa itens pendentes já existentes (ex.: chegaram antes do app ser instalado). */
    suspend fun processExisting(context: Context) {
        try {
            val buffer = Wearable.getDataClient(context).dataItems.await()
            val items = try {
                buffer.filter { it.uri.path == SyncPaths.PENDING }.map { it.freeze() }
            } finally {
                buffer.release()
            }
            items.forEach { process(context, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao ler itens pendentes", t)
        }
    }

    suspend fun connectedWatches(context: Context): List<Node> =
        runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrDefault(emptyList())

    /** Pede ao relógio para enviar os dados agora. Retorna quantos relógios receberam o pedido. */
    suspend fun requestSync(context: Context): Int {
        val nodes = connectedWatches(context)
        val mc = Wearable.getMessageClient(context)
        nodes.forEach { mc.sendMessage(it.id, SyncPaths.REQUEST_SYNC, ByteArray(0)).await() }
        return nodes.size
    }

    /** Envia a previsão de UV ao relógio. */
    suspend fun pushUv(context: Context, hours: List<UvHour>, fetchedAt: Long) {
        val req = PutDataMapRequest.create(SyncPaths.UV).apply {
            dataMap.putLongArray(SyncPaths.KEY_UV_HOURS, hours.map { it.hourStart }.toLongArray())
            dataMap.putFloatArray(SyncPaths.KEY_UV_VALUES, hours.map { it.uvi.toFloat() }.toFloatArray())
            dataMap.putLong(SyncPaths.KEY_UV_FETCHED_AT, fetchedAt)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}

class PhoneSyncListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == SyncPaths.PENDING }
            .map { it.dataItem.freeze() }
        if (items.isEmpty()) return
        runBlocking {
            items.forEach { runCatching { PhoneSync.process(this@PhoneSyncListener, it) } }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == SyncPaths.REQUEST_UV) UvWorker.runNow(this)
    }
}
