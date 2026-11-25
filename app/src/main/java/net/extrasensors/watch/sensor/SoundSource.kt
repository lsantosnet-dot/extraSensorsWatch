package net.extrasensors.watch.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import net.extrasensors.watch.domain.SoundMath

interface SoundSource {
    val available: Boolean
    /** RMS de blocos de 100 ms (PCM 16 bits, 16 kHz, mono). */
    fun blockRms(): Flow<Double>
}

class AndroidSoundSource(private val context: Context) : SoundSource {
    override val available: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    private fun audioSource(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // UNPROCESSED evita AGC/supressão de ruído; VOICE_RECOGNITION é o melhor substituto.
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    @SuppressLint("MissingPermission")
    override fun blockRms(): Flow<Double> = flow {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO não concedida" }
        val rate = 16_000
        val block = rate / 10
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            audioSource(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, block * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microfone indisponível" }
        val buf = ShortArray(block)
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < block) {
                    val n = record.read(buf, filled, block - filled)
                    if (n < 0) error("Erro de leitura do microfone ($n)")
                    filled += n
                }
                emit(SoundMath.rms(buf, block))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
