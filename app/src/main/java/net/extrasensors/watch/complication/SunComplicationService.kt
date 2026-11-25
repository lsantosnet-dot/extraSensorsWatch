package net.extrasensors.watch.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.flow.first
import net.extrasensors.watch.R
import net.extrasensors.watch.domain.Format
import net.extrasensors.watch.domain.SunExposure
import net.extrasensors.watch.graph
import net.extrasensors.watch.service.Notifications
import java.time.ZoneId

/** Complicação "Sol": tempo sob luz forte desde o protetor, contra a meta. */
class SunComplicationService : SuspendingComplicationDataSourceService() {

    private fun build(type: ComplicationType, sinceMs: Long, goalMs: Long): ComplicationData? {
        val text = PlainComplicationText.Builder(Format.durationCompact(sinceMs)).build()
        val desc = PlainComplicationText.Builder("Sol desde o protetor: ${Format.duration(sinceMs)}").build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_sun)).build()
        val tap = Notifications.openAppIntent(this, 5)
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = (sinceMs / 60_000f).coerceAtMost(goalMs / 60_000f),
                    min = 0f,
                    max = goalMs / 60_000f,
                    contentDescription = desc,
                )
                    .setText(text)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, desc)
                    .setTitle(PlainComplicationText.Builder("Sol").build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, 0L, 120 * 60_000L)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val g = graph
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val goalMs = g.settings.current().sunConfig.goalMs
        val state = g.state.sun.first()?.let { SunExposure.normalized(it, now, zone) }
        return build(request.complicationType, state?.sinceApplicationMs ?: 0L, goalMs)
    }
}
