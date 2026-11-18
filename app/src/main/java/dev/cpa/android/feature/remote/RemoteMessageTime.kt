package dev.cpa.android.feature.remote

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun messageTime(timestamp: Double?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    if (timestamp == null || !timestamp.isFinite() || timestamp <= 0 || timestamp >= Long.MAX_VALUE.toDouble()) return null
    return runCatching {
        val pattern = if (locale.language == "zh") "M月d日 HH:mm" else "MMM d, HH:mm"
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(timestamp.toLong()))
    }.getOrNull()
}
