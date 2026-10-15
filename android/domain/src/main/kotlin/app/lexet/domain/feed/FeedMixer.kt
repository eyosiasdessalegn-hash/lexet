package app.lexet.domain.feed

/**
 * Interleaves the secondary (international) stream into the primary
 * (Ethiopian) stream: one secondary item after every [ratio] primary items.
 *
 * Leftover secondary items are only appended once the primary stream is
 * exhausted (the user scrolled to the end of the archive), so a quiet week in
 * Ethiopia never turns the feed into an international feed.
 */
object FeedMixer {
    sealed interface Entry<out T> {
        val value: T
        data class Primary<T>(override val value: T) : Entry<T>
        data class Secondary<T>(override val value: T) : Entry<T>
    }

    fun <T> interleave(
        primary: List<T>,
        secondary: List<T>,
        ratio: Int = 10,
        primaryExhausted: Boolean = false,
    ): List<Entry<T>> {
        require(ratio >= 1)
        val out = ArrayList<Entry<T>>(primary.size + secondary.size)
        var s = 0
        primary.forEachIndexed { i, p ->
            out.add(Entry.Primary(p))
            if ((i + 1) % ratio == 0 && s < secondary.size) out.add(Entry.Secondary(secondary[s++]))
        }
        if (primaryExhausted) while (s < secondary.size) out.add(Entry.Secondary(secondary[s++]))
        return out
    }
}
