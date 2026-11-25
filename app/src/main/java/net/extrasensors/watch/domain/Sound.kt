package net.extrasensors.watch.domain

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object SoundMath {
    const val FULL_SCALE = 32767.0

    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            sum += x * x
        }
        return sqrt(sum / count)
    }

    /** dB = 20·log10(RMS/32767) + offset. Silêncio digital (RMS = 0) vira o piso de 0 dB. */
    fun db(rms: Double, offset: Double): Double {
        if (rms <= 0.0) return 0.0
        return max(0.0, 20.0 * log10(rms / FULL_SCALE) + offset)
    }

    fun energy(db: Double): Double = 10.0.pow(db / 10.0)
    fun fromEnergy(e: Double): Double = if (e <= 0) 0.0 else 10.0 * log10(e)

    /** Média energética (Leq) de níveis em dB. */
    fun leq(levels: List<Double>): Double =
        if (levels.isEmpty()) 0.0 else fromEnergy(levels.sumOf { energy(it) } / levels.size)
}

/** Sessão do decibelímetro: valor do último 1 s, Leq da sessão e pico de blocos de 100 ms. */
class SoundSession(private val blocksPerSecond: Int = 10) {
    private val recent = ArrayDeque<Double>()
    private var energySum = 0.0
    private var count = 0L
    var peak: Double = 0.0
        private set

    fun addBlock(db: Double) {
        recent.addLast(db)
        while (recent.size > blocksPerSecond) recent.removeFirst()
        energySum += SoundMath.energy(db)
        count++
        if (db > peak) peak = db
    }

    val hasData: Boolean get() = count > 0
    val current: Double get() = SoundMath.leq(recent.toList())
    val mean: Double get() = if (count == 0L) 0.0 else SoundMath.fromEnergy(energySum / count)
}
