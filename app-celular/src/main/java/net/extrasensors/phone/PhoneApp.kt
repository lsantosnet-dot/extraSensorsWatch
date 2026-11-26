package net.extrasensors.phone

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import net.extrasensors.phone.data.PhoneDatabase
import net.extrasensors.phone.uv.UvWorker
import net.extrasensors.shared.SyncAck

/** Preferências simples, compartilhadas entre a tela, o listener e o worker. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Notifica a interface quando algo muda (sincronização, UV, local). */
    val changes = MutableStateFlow(0L)
    private fun touch() { changes.value = System.currentTimeMillis() }

    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0)
        set(v) { sp.edit { putLong("last_sync_at", v) }; touch() }

    var uvFetchedAt: Long
        get() = sp.getLong("uv_fetched_at", 0)
        set(v) { sp.edit { putLong("uv_fetched_at", v) }; touch() }

    var uvError: String?
        get() = sp.getString("uv_error", null)
        set(v) { sp.edit { putString("uv_error", v) }; touch() }

    /** Coordenadas arredondadas a 2 casas (~1 km). */
    var location: Pair<Double, Double>?
        get() = if (sp.contains("lat")) sp.getFloat("lat", 0f).toDouble() to sp.getFloat("lon", 0f).toDouble() else null
        set(v) {
            sp.edit {
                if (v == null) { remove("lat"); remove("lon") }
                else {
                    putFloat("lat", v.first.toFloat()); putFloat("lon", v.second.toFloat())
                    putLong("location_at", System.currentTimeMillis())
                }
            }
            touch()
        }

    /** Quando a localização foi obtida pela última vez. */
    val locationAt: Long get() = sp.getLong("location_at", 0)

    var ack: SyncAck
        get() = SyncAck(
            sp.getLong("ack_bucket", 0), sp.getLong("ack_day", 0),
            sp.getLong("ack_skin", 0), sp.getLong("ack_app", 0),
        )
        set(v) {
            sp.edit {
                putLong("ack_bucket", v.bucketUpdatedAt).putLong("ack_day", v.dayUpdatedAt)
                    .putLong("ack_skin", v.skinTime).putLong("ack_app", v.applicationTime)
            }
        }
}

class PhoneGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: PhoneDatabase by lazy { PhoneDatabase.create(context) }
    val prefs = Prefs(context)
}

class PhoneApp : Application() {
    lateinit var graph: PhoneGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = PhoneGraph(this)
        runCatching { UvWorker.schedulePeriodic(this) }
    }
}

val Context.phoneGraph: PhoneGraph get() = (applicationContext as PhoneApp).graph
