package hr.fer.studentzkp.holder.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {
    private val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private val iso8601Formats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
    )

    /**
     * Format an ISO 8601 date string (e.g. "2026-09-30T00:00:00Z") into
     * a human-readable form like "Sep 30, 2026". Returns the original
     * string if parsing fails.
     */
    fun formatIso(isoDate: String?): String? {
        if (isoDate.isNullOrBlank()) return null
        for (fmt in iso8601Formats) {
            try {
                val date = fmt.parse(isoDate) ?: continue
                return displayFormat.format(date)
            } catch (_: Exception) { }
        }
        return isoDate
    }

    /** Format epoch millis to display format. */
    fun formatEpoch(epochMillis: Long): String =
        displayFormat.format(Date(epochMillis))
}
