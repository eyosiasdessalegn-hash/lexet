package app.lexet.domain.search

import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.text.EthiopicText

/**
 * Full-text search over titles, summaries, law IDs and source text in Amharic
 * and English, on SQLite FTS4 with the "simple" tokenizer.
 *
 * All linguistic work happens here, identically for the stored document and the
 * query, so results do not depend on the SQLite build on a given phone:
 * homophone folding, Ge'ez numerals, punctuation, and Amharic prefix variants
 * (the index stores "የመረጃ" and "መረጃ", so searching "መረጃ" finds both).
 */
object SearchIndex {

    data class Document(
        val titles: String,
        val summary: String,
        val lawId: String,
        val meta: String,
        val body: String,
    )

    fun document(item: LegalItem, taxonomy: Taxonomy?): Document {
        val lawIds = buildList {
            item.lawId?.let { add(it) }
            item.lawIdAm?.let { add(it) }
            item.lawNumber?.let { n ->
                add("$n")
                item.lawYearGc?.let { add("$n/$it") }
                item.lawYearEc?.let { add("$n/$it") }
            }
        }
        val meta = buildList {
            taxonomy?.issuer(item.issuer)?.let { add(it.en); add(it.am) }
            taxonomy?.issuer(item.initiator)?.let { add(it.en); add(it.am) }
            item.topics.forEach { t -> taxonomy?.topic(t)?.let { add(it.en); add(it.am) } }
            taxonomy?.docType(item.docType.code)?.let { add(it.en); add(it.am) }
            item.issuer?.let { add(it) }
        }
        val body = item.sources.joinToString(" ") { "${it.title} ${it.excerpt ?: ""} ${it.name}" }
        return Document(
            titles = EthiopicText.indexText(listOfNotNull(item.titleEn, item.titleAm).joinToString(" ")),
            summary = EthiopicText.indexText(listOfNotNull(item.summaryEn, item.summaryAm).joinToString(" ")),
            lawId = EthiopicText.indexText(lawIds.joinToString(" ")),
            meta = EthiopicText.indexText(meta.joinToString(" ")),
            body = EthiopicText.indexText(body.take(60_000)),
        )
    }

    /**
     * FTS4 MATCH expression for what the user typed, or null if nothing searchable.
     * Every word must match (implicit AND); words of 2+ characters match as prefixes.
     */
    fun matchExpression(userQuery: String): String? {
        val terms = EthiopicText.tokens(userQuery).filter { it.isNotBlank() }.take(12)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { t -> if (t.length >= 2) "$t*" else t }
    }
}
