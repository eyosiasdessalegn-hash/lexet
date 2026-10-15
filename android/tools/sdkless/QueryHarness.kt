import app.lexet.data.*
import app.lexet.domain.model.*
import app.lexet.domain.notify.TypeFilter
import app.lexet.domain.search.SearchIndex
import java.io.File
import java.time.LocalDate

// Emits sample items (with their FTS documents) and archive queries as JSON for an SQLite check in Python.
fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
fun main(args: Array<String>) {
    val tax = Taxonomy.parse(File(args[0]).readText())
    fun it(id: String, doc: DocType, status: LegalStatus, ver: Verification, en: String?, am: String?, n: Int?, topics: List<String>, issuer: String, day: String, jur: Jurisdiction = Jurisdiction.ET) =
        LegalItem(id, 1, jur, doc, n?.let { "${if (doc == DocType.DIRECTIVE) "Directive" else "Proclamation"} No. $it/2026" }, n, n?.let { 2026 }, n?.let { 2018 },
            en, am, null, null, issuer, null, status, if (ver == Verification.VERIFIED) status else null, status, ver, 3, true, topics, null,
            LocalDate.parse(day), LocalDate.parse(day), null, null, emptyList())
    val items = listOf(
        it("foi", DocType.PROCLAMATION, LegalStatus.IN_FORCE, Verification.VERIFIED, "Freedom of Information Proclamation", "የመረጃ ነፃነት አዋጅ", 1427, listOf("rights"), "HPR", "2026-06-25"),
        it("dig", DocType.PROCLAMATION, LegalStatus.TABLED, Verification.VERIFIED, "Digital Commerce Proclamation", null, null, listOf("digital", "customs"), "HPR", "2026-10-01"),
        it("tax", DocType.PROCLAMATION, LegalStatus.APPROVED, Verification.UNCONFIRMED, "Excise Tax (Amendment) Proclamation", "የኤክሳይዝ ታክስ ማሻሻያ አዋጅ", null, listOf("tax"), "HPR", "2026-10-10"),
        it("efda", DocType.DIRECTIVE, LegalStatus.IN_FORCE, Verification.UNCONFIRMED, "Cosmetics Import Directive", "የኮስሞቲክስ ማስመጫ መመሪያ", 1050, listOf("health"), "EFDA", "2026-09-20"),
        it("au", DocType.OTHER, LegalStatus.IN_FORCE, Verification.REPORTED, "AfCFTA rules adopted", null, null, emptyList(), "OTHER", "2026-10-12", Jurisdiction.INTL),
    )
    val sb = StringBuilder("{\"items\":[")
    items.forEachIndexed { i, x ->
        val d = SearchIndex.document(x, tax)
        if (i > 0) sb.append(',')
        sb.append("{\"id\":${esc(x.id)},\"doc\":${i + 1},\"jur\":${esc(x.jurisdiction.name)},\"docType\":${esc(x.docType.code)},\"status\":${esc(x.status!!.code)},")
        sb.append("\"verifiedStatus\":${x.verifiedStatus?.let { esc(it.code) } ?: "null"},\"verification\":${esc(x.verification.code)},\"issuer\":${esc(x.issuer!!)},")
        sb.append("\"topics\":${esc(if (x.topics.isEmpty()) "" else x.topics.joinToString(",", ",", ","))},\"day\":${x.eventDate!!.toEpochDay()},")
        sb.append("\"fts\":[${esc(d.titles)},${esc(d.summary)},${esc(d.lawId)},${esc(d.meta)},${esc(d.body)}]}")
    }
    sb.append("],\"queries\":[")
    val today = LocalDate.of(2026, 10, 15)
    val cases = listOf(
        "default" to ArchiveFilter(),
        "amharic_info" to ArchiveFilter(text = "መረጃ"),
        "amharic_variant" to ArchiveFilter(text = "ነጻነት"),
        "law_ec_year" to ArchiveFilter(text = "1427/2018"),
        "law_gc_year" to ArchiveFilter(text = "proclamation 1427/2026"),
        "issuer_text_am" to ArchiveFilter(text = "ምግብና መድኃኒት"),
        "draft_bills" to ArchiveFilter(types = setOf(TypeFilter.DRAFT_BILL)),
        "imminent" to ArchiveFilter(urgencies = setOf(Urgency.IMMINENT)),
        "verified_30d" to ArchiveFilter(verification = VerificationFilter.VERIFIED, sinceDays = 30),
        "topic_tax_or_health" to ArchiveFilter(topics = setOf("tax", "health")),
        "intl" to ArchiveFilter(jurisdiction = JurisdictionFilter.INTERNATIONAL),
        "all_bookmarked" to ArchiveFilter(jurisdiction = JurisdictionFilter.ALL, bookmarkedOnly = true),
        "tagged" to ArchiveFilter(jurisdiction = JurisdictionFilter.ALL, tagId = 1),
        "issuer_efda" to ArchiveFilter(issuer = "EFDA"),
        "unread" to ArchiveFilter(unreadOnly = true),
    )
    cases.forEachIndexed { i, (name, f) ->
        val (sql, a) = buildArchiveQuery(f, 500, today)
        if (i > 0) sb.append(',')
        sb.append("{\"name\":${esc(name)},\"sql\":${esc(sql)},\"args\":[${a.joinToString(",") { v -> if (v is String) esc(v) else v.toString() }}]}")
    }
    sb.append("]}")
    println(sb)
}
