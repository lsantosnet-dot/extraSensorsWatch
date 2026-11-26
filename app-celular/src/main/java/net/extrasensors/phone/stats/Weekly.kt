package net.extrasensors.phone.stats

import net.extrasensors.phone.data.HrvRow
import net.extrasensors.shared.HrvStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Tom da leitura, para a cor do texto na tela. */
enum class Tone { GOOD, NEUTRAL, ATTENTION, INFO }

data class Insight(val tone: Tone, val text: String)

data class WeeklyReport(
    /** Medições de HRV na semana (últimos 7 dias, hoje incluído). */
    val hrvCount: Int,
    /** Dias da semana cuja última medição ficou abaixo da faixa pessoal. */
    val daysBelow: Int,
    /** Dias medidos seguidos, até o mais recente, abaixo da faixa. */
    val belowStreak: Int,
    val meanRmssd: Double?,
    val previousMeanRmssd: Double?,
    /** Variação da média de RMSSD contra a semana anterior (fração, 0,1 = +10%). */
    val rmssdChange: Double?,
    val edaSessions: Int,
    val edaPeaksPerMin: Double?,
    /** Mediana de picos/min das sessões dos 30 dias anteriores à semana. */
    val edaReference: Double?,
    val insights: List<Insight>,
)

/**
 * Resumo semanal de estresse e recuperação. Regras simples e explicáveis, sempre contra o seu
 * próprio histórico:
 * - recuperação baixa: 2+ dias seguidos ou 3+ dias na semana abaixo da faixa de HRV;
 * - recuperação melhorando/caindo: média de RMSSD 5% acima/abaixo da semana anterior;
 * - ativação alta: picos de EDA por minuto 50% acima da sua mediana dos 30 dias anteriores;
 * - estresse acumulado: recuperação baixa e ativação alta na mesma semana.
 */
object WeeklyStress {
    private const val DAY = 86_400_000L

    private fun day(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    fun compute(
        hrvAll: List<HrvRow>,
        eda: List<EdaSessionSummary>,
        today: LocalDate,
        zone: ZoneId,
    ): WeeklyReport {
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStart = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // HRV da semana com a faixa pessoal de cada medição.
        val week = StressStats.hrvSeries(hrvAll, weekStart, end)
        val prev = StressStats.hrvSeries(hrvAll, prevStart, weekStart)
        val lastPerDay = week.groupBy { day(it.timeMillis, zone) }.toSortedMap().mapValues { it.value.last() }
        val daysBelow = lastPerDay.values.count { it.status == HrvStatus.BELOW }
        val streak = lastPerDay.values.reversed().takeWhile { it.status == HrvStatus.BELOW }.count()
        val mean = week.takeIf { it.isNotEmpty() }?.map { it.rmssdMs }?.average()
        val prevMean = prev.takeIf { it.isNotEmpty() }?.map { it.rmssdMs }?.average()
        val change = if (mean != null && prevMean != null && prevMean > 0) mean / prevMean - 1 else null

        // EDA: picos/min da semana contra a mediana das sessões dos 30 dias anteriores.
        val edaWeek = eda.filter { it.startedAt in weekStart until end && it.peaksPerMin != null }
        val edaBefore = eda.filter { it.startedAt in (weekStart - 30 * DAY) until weekStart && it.peaksPerMin != null }
        val edaRate = edaWeek.takeIf { it.isNotEmpty() }?.let { s ->
            // Média ponderada pela duração: sessões longas pesam mais.
            s.sumOf { it.peaksPerMin!! * it.durationMs } / s.sumOf { it.durationMs }.coerceAtLeast(1)
        }
        val reference = edaBefore.takeIf { it.size >= 3 }?.map { it.peaksPerMin!! }?.sorted()?.let { v ->
            if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        }

        val insights = mutableListOf<Insight>()
        val lowRecovery = streak >= 2 || daysBelow >= 3
        val highActivation = edaRate != null && reference != null && reference > 0 && edaRate > reference * 1.5

        when {
            week.size < 3 -> insights += Insight(
                Tone.INFO,
                "Poucas medições de HRV nesta semana (${week.size}). Meça 1 min ao acordar, todo dia, para o resumo ficar confiável.",
            )
            week.none { it.status != null } -> insights += Insight(
                Tone.INFO,
                "Sua faixa pessoal de HRV ainda está se formando: são precisas 3 medições nos 7 dias anteriores.",
            )
            lowRecovery && highActivation -> insights += Insight(
                Tone.ATTENTION,
                "Padrão de estresse acumulado: recuperação abaixo da sua faixa ($daysBelow dia(s) na semana) e ativação nas " +
                    "sessões de EDA acima do seu habitual. Vale reduzir a carga e priorizar o sono.",
            )
            lowRecovery -> insights += Insight(
                Tone.ATTENTION,
                if (streak >= 2) "Recuperação baixa: $streak dia(s) seguidos abaixo da sua faixa de HRV. " +
                    "Pode ser estresse, sono ruim, treino pesado, álcool ou uma doença começando."
                else "Recuperação baixa em $daysBelow dias da semana. Veja o que esses dias tiveram em comum.",
            )
            daysBelow == 1 -> insights += Insight(
                Tone.NEUTRAL,
                "Um dia abaixo da faixa nesta semana: isolado, costuma ser uma noite ruim, não um padrão.",
            )
            else -> insights += Insight(Tone.GOOD, "Recuperação dentro da sua faixa durante a semana.")
        }
        if (change != null && week.size >= 3) {
            val pct = kotlin.math.round(change * 100).toInt()
            when {
                change >= 0.05 -> insights += Insight(Tone.GOOD, "HRV média $pct% acima da semana anterior: recuperação melhorando.")
                change <= -0.05 -> insights += Insight(Tone.ATTENTION, "HRV média ${-pct}% abaixo da semana anterior: tendência de queda.")
                else -> insights += Insight(Tone.NEUTRAL, "HRV média estável em relação à semana anterior.")
            }
        }
        when {
            edaWeek.isEmpty() -> insights += Insight(
                Tone.INFO,
                "Sem sessões de EDA nesta semana. Grave 5 min de base tranquilo e depois as situações que quer comparar.",
            )
            reference == null -> insights += Insight(
                Tone.INFO,
                "EDA: ainda sem referência (são precisas 3 sessões nos 30 dias anteriores). Continue gravando.",
            )
            highActivation -> if (!lowRecovery) insights += Insight(
                Tone.ATTENTION,
                "Ativação acima do seu habitual nas sessões de EDA desta semana. Lembre: a EDA mede intensidade, " +
                    "não se é bom ou ruim; você sabe o contexto.",
            )
            edaRate != null && edaRate < reference * 0.67 -> insights += Insight(
                Tone.GOOD, "Ativação nas sessões de EDA abaixo do seu habitual.",
            )
            else -> insights += Insight(Tone.NEUTRAL, "Ativação nas sessões de EDA dentro do seu habitual.")
        }

        return WeeklyReport(
            hrvCount = week.size,
            daysBelow = daysBelow,
            belowStreak = streak,
            meanRmssd = mean,
            previousMeanRmssd = prevMean,
            rmssdChange = change,
            edaSessions = edaWeek.size,
            edaPeaksPerMin = edaRate,
            edaReference = reference,
            insights = insights,
        )
    }
}
