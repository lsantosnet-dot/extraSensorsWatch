package net.extrasensors.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Caminhos do Wearable Data Layer usados pelos dois apps. */
object SyncPaths {
    /** Relógio → celular: dados pendentes (DataItem com Asset). */
    const val PENDING = "/sync/pending"
    /** Celular → relógio: recibo do que já foi gravado. */
    const val ACK = "/ack"
    /** Celular → relógio: previsão horária do índice UV. */
    const val UV = "/uv"
    /** Mensagem celular → relógio: enviar dados agora. */
    const val REQUEST_SYNC = "/request-sync"
    /** Mensagem relógio → celular: atualizar a previsão de UV. */
    const val REQUEST_UV = "/request-uv"

    const val KEY_PAYLOAD = "payload"
    const val KEY_CREATED_AT = "created_at"
    const val KEY_UV_HOURS = "uv_hours"
    const val KEY_UV_VALUES = "uv_values"
    const val KEY_UV_FETCHED_AT = "uv_fetched_at"
    const val KEY_ACK_BUCKET = "ack_bucket_updated"
    const val KEY_ACK_DAY = "ack_day_updated"
    const val KEY_ACK_SKIN = "ack_skin_time"
    const val KEY_ACK_APPLICATION = "ack_application_time"
}

/** Bloco de 5 min de exposição solar. */
data class SunBucket(val bucketStart: Long, val exposedMs: Long, val doseSed: Double, val maxUv: Double?, val updatedAt: Long)

/** Total diário do relógio (dia como epochDay no fuso do relógio). */
data class SunDayTotal(val epochDay: Long, val totalMs: Long, val updatedAt: Long)

data class SkinReading(val timeMillis: Long, val celsius: Double)

data class SunscreenApplication(val timeMillis: Long)

data class SyncPayload(
    val buckets: List<SunBucket>,
    val days: List<SunDayTotal>,
    val skin: List<SkinReading>,
    val applications: List<SunscreenApplication>,
) {
    val isEmpty: Boolean get() = buckets.isEmpty() && days.isEmpty() && skin.isEmpty() && applications.isEmpty()
}

/** Recibo: maiores valores já gravados no celular, por tabela. */
data class SyncAck(
    val bucketUpdatedAt: Long = 0,
    val dayUpdatedAt: Long = 0,
    val skinTime: Long = 0,
    val applicationTime: Long = 0,
) {
    fun merge(o: SyncAck) = SyncAck(
        maxOf(bucketUpdatedAt, o.bucketUpdatedAt),
        maxOf(dayUpdatedAt, o.dayUpdatedAt),
        maxOf(skinTime, o.skinTime),
        maxOf(applicationTime, o.applicationTime),
    )

    companion object {
        fun of(p: SyncPayload) = SyncAck(
            p.buckets.maxOfOrNull { it.updatedAt } ?: 0,
            p.days.maxOfOrNull { it.updatedAt } ?: 0,
            p.skin.maxOfOrNull { it.timeMillis } ?: 0,
            p.applications.maxOfOrNull { it.timeMillis } ?: 0,
        )
    }
}

/** Codificação binária compacta + gzip. Versionada para permitir evoluir o formato. */
object SyncCodec {
    private const val VERSION = 1

    fun encode(p: SyncPayload): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(VERSION)
            out.writeInt(p.buckets.size)
            p.buckets.forEach {
                out.writeLong(it.bucketStart); out.writeLong(it.exposedMs); out.writeDouble(it.doseSed)
                out.writeDouble(it.maxUv ?: -1.0); out.writeLong(it.updatedAt)
            }
            out.writeInt(p.days.size)
            p.days.forEach { out.writeLong(it.epochDay); out.writeLong(it.totalMs); out.writeLong(it.updatedAt) }
            out.writeInt(p.skin.size)
            p.skin.forEach { out.writeLong(it.timeMillis); out.writeDouble(it.celsius) }
            out.writeInt(p.applications.size)
            p.applications.forEach { out.writeLong(it.timeMillis) }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): SyncPayload {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { inp ->
            val v = inp.readInt()
            require(v == VERSION) { "Versão de sincronização desconhecida: $v" }
            val buckets = List(inp.readInt()) {
                val start = inp.readLong(); val exp = inp.readLong(); val dose = inp.readDouble()
                val uv = inp.readDouble(); val upd = inp.readLong()
                SunBucket(start, exp, dose, uv.takeIf { it >= 0 }, upd)
            }
            val days = List(inp.readInt()) { SunDayTotal(inp.readLong(), inp.readLong(), inp.readLong()) }
            val skin = List(inp.readInt()) { SkinReading(inp.readLong(), inp.readDouble()) }
            val apps = List(inp.readInt()) { SunscreenApplication(inp.readLong()) }
            return SyncPayload(buckets, days, skin, apps)
        }
    }
}
