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
    const val KEY_ACK_HRV = "ack_hrv_time"
    const val KEY_ACK_EDA_SESSION = "ack_eda_session_updated"
    const val KEY_ACK_EDA_POINT = "ack_eda_point_time"
}

/** Bloco de 5 min de exposição solar. */
data class SunBucket(val bucketStart: Long, val exposedMs: Long, val doseSed: Double, val maxUv: Double?, val updatedAt: Long)

/** Total diário do relógio (dia como epochDay no fuso do relógio). */
data class SunDayTotal(val epochDay: Long, val totalMs: Long, val updatedAt: Long)

data class SkinReading(val timeMillis: Long, val celsius: Double)

data class SunscreenApplication(val timeMillis: Long)

/** Medição de HRV (1 min parado): RMSSD em ms, batimento médio e batimentos válidos usados. */
data class HrvMeasurement(val timeMillis: Long, val rmssdMs: Double, val meanHr: Double, val beats: Int, val durationMs: Long)

/** Sessão de atividade eletrodérmica. [source]: 0 = manual, 1 = corrida da tela Suor. */
data class EdaSession(val startedAt: Long, val endedAt: Long?, val source: Int, val updatedAt: Long)

/** Janela de 10 s de EDA: condutância média, mínima e máxima (µS) e picos de ativação na janela. */
data class EdaPoint(val timeMillis: Long, val sessionStart: Long, val mean: Double, val min: Double, val max: Double, val peaks: Int)

data class SyncPayload(
    val buckets: List<SunBucket>,
    val days: List<SunDayTotal>,
    val skin: List<SkinReading>,
    val applications: List<SunscreenApplication>,
    val hrv: List<HrvMeasurement> = emptyList(),
    val edaSessions: List<EdaSession> = emptyList(),
    val edaPoints: List<EdaPoint> = emptyList(),
) {
    val isEmpty: Boolean
        get() = buckets.isEmpty() && days.isEmpty() && skin.isEmpty() && applications.isEmpty() &&
            hrv.isEmpty() && edaSessions.isEmpty() && edaPoints.isEmpty()
}

/** Recibo: maiores valores já gravados no celular, por tabela. */
data class SyncAck(
    val bucketUpdatedAt: Long = 0,
    val dayUpdatedAt: Long = 0,
    val skinTime: Long = 0,
    val applicationTime: Long = 0,
    val hrvTime: Long = 0,
    val edaSessionUpdatedAt: Long = 0,
    val edaPointTime: Long = 0,
) {
    fun merge(o: SyncAck) = SyncAck(
        maxOf(bucketUpdatedAt, o.bucketUpdatedAt),
        maxOf(dayUpdatedAt, o.dayUpdatedAt),
        maxOf(skinTime, o.skinTime),
        maxOf(applicationTime, o.applicationTime),
        maxOf(hrvTime, o.hrvTime),
        maxOf(edaSessionUpdatedAt, o.edaSessionUpdatedAt),
        maxOf(edaPointTime, o.edaPointTime),
    )

    companion object {
        fun of(p: SyncPayload) = SyncAck(
            p.buckets.maxOfOrNull { it.updatedAt } ?: 0,
            p.days.maxOfOrNull { it.updatedAt } ?: 0,
            p.skin.maxOfOrNull { it.timeMillis } ?: 0,
            p.applications.maxOfOrNull { it.timeMillis } ?: 0,
            p.hrv.maxOfOrNull { it.timeMillis } ?: 0,
            p.edaSessions.maxOfOrNull { it.updatedAt } ?: 0,
            p.edaPoints.maxOfOrNull { it.timeMillis } ?: 0,
        )
    }
}

/** Codificação binária compacta + gzip. Versionada para permitir evoluir o formato. */
object SyncCodec {
    /** v1: sol, pele e protetor. v2: + HRV e EDA. A leitura aceita as duas. */
    private const val VERSION = 2

    fun encode(p: SyncPayload, version: Int = VERSION): ByteArray {
        require(version == VERSION || (version == 1 && p.hrv.isEmpty() && p.edaSessions.isEmpty() && p.edaPoints.isEmpty()))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(version)
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
            if (version == 1) return@use
            out.writeInt(p.hrv.size)
            p.hrv.forEach {
                out.writeLong(it.timeMillis); out.writeDouble(it.rmssdMs); out.writeDouble(it.meanHr)
                out.writeInt(it.beats); out.writeLong(it.durationMs)
            }
            out.writeInt(p.edaSessions.size)
            p.edaSessions.forEach {
                out.writeLong(it.startedAt); out.writeLong(it.endedAt ?: -1); out.writeInt(it.source); out.writeLong(it.updatedAt)
            }
            out.writeInt(p.edaPoints.size)
            p.edaPoints.forEach {
                out.writeLong(it.timeMillis); out.writeLong(it.sessionStart); out.writeDouble(it.mean)
                out.writeDouble(it.min); out.writeDouble(it.max); out.writeInt(it.peaks)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): SyncPayload {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { inp ->
            val v = inp.readInt()
            require(v in 1..VERSION) { "Versão de sincronização desconhecida: $v" }
            val buckets = List(inp.readInt()) {
                val start = inp.readLong(); val exp = inp.readLong(); val dose = inp.readDouble()
                val uv = inp.readDouble(); val upd = inp.readLong()
                SunBucket(start, exp, dose, uv.takeIf { it >= 0 }, upd)
            }
            val days = List(inp.readInt()) { SunDayTotal(inp.readLong(), inp.readLong(), inp.readLong()) }
            val skin = List(inp.readInt()) { SkinReading(inp.readLong(), inp.readDouble()) }
            val apps = List(inp.readInt()) { SunscreenApplication(inp.readLong()) }
            if (v == 1) return SyncPayload(buckets, days, skin, apps)
            val hrv = List(inp.readInt()) {
                HrvMeasurement(inp.readLong(), inp.readDouble(), inp.readDouble(), inp.readInt(), inp.readLong())
            }
            val sessions = List(inp.readInt()) {
                val start = inp.readLong(); val end = inp.readLong(); val src = inp.readInt(); val upd = inp.readLong()
                EdaSession(start, end.takeIf { it >= 0 }, src, upd)
            }
            val points = List(inp.readInt()) {
                EdaPoint(inp.readLong(), inp.readLong(), inp.readDouble(), inp.readDouble(), inp.readDouble(), inp.readInt())
            }
            return SyncPayload(buckets, days, skin, apps, hrv, sessions, points)
        }
    }
}
