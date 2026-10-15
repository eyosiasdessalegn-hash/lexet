package app.lexet.domain.model

/** A code with English and Amharic labels. */
data class Label(val code: String, val en: String, val am: String) {
    fun text(amharic: Boolean) = if (amharic) am else en
}

/**
 * Bilingual labels for document types, statuses, urgencies, issuing bodies and
 * topics. Loaded from shared/taxonomy.json (bundled as an app asset), the same
 * file the backend uses, so codes always agree.
 */
class Taxonomy(
    val docTypes: List<Label>,
    val statuses: List<Label>,
    val urgencies: List<Label>,
    val issuers: List<Label>,
    val topics: List<Label>,
) {
    private val byKind = mapOf(
        "doc" to docTypes.associateBy { it.code },
        "status" to statuses.associateBy { it.code },
        "urgency" to urgencies.associateBy { it.code },
        "issuer" to issuers.associateBy { it.code },
        "topic" to topics.associateBy { it.code },
    )

    fun docType(code: String?) = byKind.getValue("doc")[code]
    fun status(code: String?) = byKind.getValue("status")[code]
    fun urgency(code: String?) = byKind.getValue("urgency")[code]
    fun issuer(code: String?) = byKind.getValue("issuer")[code]
    fun topic(code: String?) = byKind.getValue("topic")[code]

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): Taxonomy {
            val root = MiniJson.parse(json) as Map<String, Any?>
            fun labels(key: String) = (root[key] as List<Map<String, Any?>>).map {
                Label(it["code"] as String, it["en"] as String, it["am"] as String)
            }
            return Taxonomy(labels("doc_types"), labels("statuses"), labels("urgencies"),
                labels("issuers"), labels("topics"))
        }
    }
}
