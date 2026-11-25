package net.extrasensors.watch.domain

/**
 * Soma subida e descida com histerese: só conta quando a altitude se afasta mais de
 * [hysteresis] metros do último ponto confirmado, para o ruído do barômetro não inflar o total.
 */
data class ClimbAccumulator(
    val hysteresis: Double = 2.0,
    val reference: Double? = null,
    val gain: Double = 0.0,
    val loss: Double = 0.0,
) {
    fun add(altitude: Double): ClimbAccumulator {
        val ref = reference ?: return copy(reference = altitude)
        val diff = altitude - ref
        return when {
            diff >= hysteresis -> copy(reference = altitude, gain = gain + diff)
            diff <= -hysteresis -> copy(reference = altitude, loss = loss - diff)
            else -> this
        }
    }
}
