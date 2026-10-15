package app.lexet.domain.notify

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A notification waiting for the next digest. */
data class PendingNotice(
    val itemId: String,
    val title: String,
    val lawId: String?,
    val kind: ChangeKind,
    val statusPhrase: String,
    val createdAt: Instant,
)

data class DigestContent(val title: String, val lines: List<String>, val more: Int, val itemIds: List<String>)

object DigestPlanner {
    const val MAX_LINES = 6

    fun build(pending: List<PendingNotice>, amharic: Boolean): DigestContent? {
        if (pending.isEmpty()) return null
        // One line per item (an item can have queued both "verified" and a status change).
        val latest = pending.sortedBy { it.createdAt }.associateBy { it.itemId }.values
            .sortedByDescending { it.createdAt }
        val n = latest.size
        val title = if (amharic) "$n አዲስ የተረጋገጡ የሕግ ለውጦች" else
            "$n verified legal development${if (n == 1) "" else "s"}"
        val lines = latest.take(MAX_LINES).map { p ->
            buildString {
                append(p.lawId?.let { "$it · " } ?: "")
                append(p.title)
                append(" — ")
                append(p.statusPhrase)
            }
        }
        return DigestContent(title, lines, (n - MAX_LINES).coerceAtLeast(0), latest.map { it.itemId })
    }

    /** Delay until the next digest: top of the next hour, or the next [dailyHour] local time. */
    fun delayUntilNext(mode: DigestMode, now: ZonedDateTime, dailyHour: Int = 8): Duration = when (mode) {
        DigestMode.INSTANT -> Duration.ZERO
        DigestMode.HOURLY -> Duration.between(now, now.withMinute(0).withSecond(0).withNano(0).plusHours(1))
        DigestMode.DAILY -> {
            var next = now.withHour(dailyHour).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            Duration.between(now, next)
        }
    }

    fun delayUntilNext(mode: DigestMode, now: Instant, zone: ZoneId, dailyHour: Int = 8): Duration =
        delayUntilNext(mode, now.atZone(zone), dailyHour)
}
