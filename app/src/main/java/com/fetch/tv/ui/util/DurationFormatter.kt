package com.fetch.tv.ui.util

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

/**
 * A runtime in the app language's short form ("1h 32m" in English), with units and order taken
 * from the locale's own data. Whole hours drop the minutes; under an hour shows minutes only.
 */
fun formatRuntimeMinutes(minutes: Int, locale: Locale = Locale.getDefault()): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return formatDuration(
        Measure(hours, MeasureUnit.HOUR).takeIf { hours > 0 },
        Measure(mins, MeasureUnit.MINUTE).takeIf { mins > 0 || hours == 0 },
        locale = locale
    )
}

/** Units in the app language's narrow form, skipping nulls, e.g. "2d 4h" or "5m 12s". */
fun formatDuration(vararg parts: Measure?, locale: Locale = Locale.getDefault()): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
        .formatMeasures(*parts.filterNotNull().toTypedArray())
