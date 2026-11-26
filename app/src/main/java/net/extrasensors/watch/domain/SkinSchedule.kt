package net.extrasensors.watch.domain

/**
 * Espera crescente entre tentativas: começa em [minMs], dobra a cada falha e para em [maxMs].
 * [reset] volta ao início depois de uma tentativa que deu certo.
 */
class Backoff(private val minMs: Long, private val maxMs: Long) {
    private var current = minMs

    fun next(): Long {
        val d = current
        current = (current * 2).coerceAtMost(maxMs)
        return d
    }

    fun reset() {
        current = minMs
    }
}

/** Decisões de agenda da temperatura da pele em segundo plano, separadas do serviço para teste. */
object SkinSchedule {
    const val PERIOD_MS = 30 * 60_000L

    /**
     * Medição sob demanda periódica: roda quando o relógio mede pele ([supported]) e a última
     * leitura gravada tem 30 min ou mais, qualquer que seja o modo, exceto enquanto a leitura
     * contínua está de fato coletando. Assim, se a contínua cair e estiver esperando para tentar
     * de novo, os dados não param.
     */
    fun onDemandDue(supported: Boolean, continuousCollecting: Boolean, lastSavedAt: Long?, now: Long): Boolean = when {
        !supported -> false
        continuousCollecting -> false
        lastSavedAt != null && now - lastSavedAt < PERIOD_MS -> false
        else -> true
    }
}
