package app.lexet.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.lexet.R
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.report.Report
import app.lexet.domain.text.EthiopianCalendar
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a [Report] to a multi-page A4 PDF with a cover, a table of contents
 * with page numbers, one block per instrument (law IDs in both calendars,
 * titles in both languages, status, verification, summary, numbered source
 * citations) and a reference list with full URLs.
 *
 * Text is laid out with StaticLayout, so Amharic is shaped with the system's
 * Ethiopic font. Long paragraphs split across pages. android.graphics.pdf
 * cannot create link annotations, so URLs are printed in full; the in-app
 * report view has tappable links.
 */
class ReportPdfWriter(private val context: Context, private val taxonomy: Taxonomy?) {
    private val pageW = 595
    private val pageH = 842
    private val margin = 48f
    private val footerH = 26f
    private val contentW = (pageW - 2 * margin).toInt()
    private val bottom = pageH - margin - footerH
    private val brand = Color.rgb(0x1B, 0x2A, 0x4A)   // Aristophanes navy
    private val muted = Color.rgb(0x5F, 0x63, 0x68)

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    private val pTitle = paint(22f, bold = true, color = brand)
    private val pH1 = paint(15f, bold = true, color = brand)
    private val pH2 = paint(12f, bold = true)
    private val pBody = paint(10.5f)
    private val pSmall = paint(9f, color = muted)
    private val pEntry = paint(10.5f, bold = true, color = brand)

    private class Para(
        val layout: StaticLayout,
        val spaceBefore: Float,
        val keepWithNext: Boolean = false,
        val indent: Float = 0f,
        val entryNumber: Int? = null,
        val tocTarget: Int? = null,
    )

    private class Placed(val page: Int, val y: Float, val para: Para, val start: Int, val end: Int)

    private fun para(text: CharSequence, p: TextPaint, spaceBefore: Float = 4f, keep: Boolean = false,
                     indent: Float = 0f, width: Int = contentW, entry: Int? = null, toc: Int? = null): Para {
        val w = (width - indent).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.18f)
            .setIncludePad(false)
            .build()
        return Para(layout, spaceBefore, keep, indent, entry, toc)
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    fun write(report: Report, file: File, amharicFirst: Boolean) {
        val doc = PdfDocument()
        try {
            renderPages(report, amharicFirst) { pageIndex, _, drawPage ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
                drawPage(page.canvas)
                doc.finishPage(page)
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /**
     * Lays the report out (two passes, so the table of contents knows each entry's
     * page) and hands every A4 page (595 × 842 pt) to [onPage] to be drawn onto a canvas.
     * [write] draws onto PDF pages; tests draw onto bitmaps to check the layout.
     */
    fun renderPages(report: Report, amharicFirst: Boolean, onPage: (index: Int, total: Int, draw: (Canvas) -> Unit) -> Unit) {
        val cover = coverParas(report, amharicFirst)
        val toc = tocParas(report, amharicFirst)
        val body = bodyParas(report, amharicFirst)

        val (coverPl, coverPages) = paginate(cover, 0)
        val (tocPl, tocPages) = paginate(toc, coverPages)
        val (bodyPl, bodyPages) = paginate(body, coverPages + tocPages)
        val total = coverPages + tocPages + bodyPages
        val entryPage = bodyPl.filter { it.para.entryNumber != null && it.start == 0 }
            .associate { it.para.entryNumber!! to it.page + 1 }

        val all = (coverPl + tocPl + bodyPl).groupBy { it.page }
        for (pageIndex in 0 until total) {
            onPage(pageIndex, total) { c ->
                all[pageIndex].orEmpty().forEach { draw(c, it, entryPage) }
                footer(c, report, pageIndex + 1, total, amharicFirst)
            }
        }
    }

    // ------------------------------------------------------------------ content

    private fun dates(d: LocalDate?): String = d?.let {
        "${EthiopianCalendar.formatGregorian(it, false)} · ${EthiopianCalendar.format(it, true)}"
    } ?: "—"

    private fun coverParas(r: Report, am: Boolean): List<Para> {
        val out = ArrayList<Para>()
        out += para("Aristophanes", pSmall, 0f)
        out += para(s(R.string.pdf_title), pTitle, 6f)
        out += para(r.period.label(am), pH1, 14f)
        out += para(r.period.label(!am), pH2, 2f)
        out += para(s(R.string.pdf_range, dates(r.period.start), dates(r.period.endInclusive)), pBody, 10f)
        val generated = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(r.generatedAt)
        out += para(s(R.string.pdf_generated, generated), pSmall, 4f)
        out += para(s(R.string.pdf_totals_heading), pH2, 22f)
        out += para(s(R.string.pdf_totals, r.totals["new"] ?: 0, r.totals["status_changes"] ?: 0,
            r.totals["verified"] ?: 0, r.totals["unconfirmed"] ?: 0), pBody, 4f)
        out += para(s(R.string.pdf_verification_note), pBody, 18f)
        out += para(s(R.string.pdf_disclaimer), pSmall, 18f)
        if (r.isEmpty) out += para(s(R.string.pdf_empty), pH2, 30f)
        return out
    }

    private fun entryHeading(n: Int, item: LegalItem, am: Boolean): String {
        val ids = listOfNotNull(item.lawId, item.lawIdAm).let { if (am) it.reversed() else it }
        return if (ids.isEmpty()) "$n." else "$n.  ${ids.joinToString("  ·  ")}"
    }

    private fun tocParas(r: Report, am: Boolean): List<Para> {
        if (r.isEmpty) return emptyList()
        val out = ArrayList<Para>()
        out += para(s(R.string.pdf_contents), pH1, 0f)
        for (sec in r.sections) {
            out += para(if (am) sec.titleAm else sec.titleEn, pH2, 10f, keep = true)
            for (e in sec.entries) {
                val text = "${e.number}.  ${listOfNotNull(e.item.lawId?.let { "$it — " }).joinToString("")}${e.item.title(am)}"
                out += para(text, pBody, 3f, indent = 10f, width = contentW - 36, toc = e.number)
            }
        }
        if (r.citations.isNotEmpty()) out += para(s(R.string.pdf_references), pH2, 10f)
        return out
    }

    private fun bodyParas(r: Report, am: Boolean): List<Para> {
        val out = ArrayList<Para>()
        for (sec in r.sections) {
            out += para(if (am) sec.titleAm else sec.titleEn, pH1, 16f, keep = true)
            for (e in sec.entries) {
                val item = e.item
                out += para(entryHeading(e.number, item, am), pEntry, 14f, keep = true, entry = e.number)
                val primary = item.title(am)
                out += para(primary, pH2, 2f, keep = true)
                val secondary = if (am) item.titleEn else item.titleAm
                if (secondary != null && secondary != primary) out += para(secondary, pBody, 1f)
                val issuer = taxonomy?.issuer(item.issuer)?.text(am) ?: item.issuer
                val status = (item.verifiedStatus ?: item.status)?.let { st -> taxonomy?.status(st.code)?.text(am) ?: st.code }
                val meta = buildList {
                    if (e.statusChange && status != null) add(s(R.string.pdf_status_change, status))
                    issuer?.let { v -> add(s(R.string.pdf_issuer, v)) }
                    status?.let { v -> add(s(R.string.pdf_status, v)) }
                    add(s(R.string.pdf_first_reported, dates(item.eventDate)))
                    if (item.statusDate != null && item.statusDate != item.eventDate) add(s(R.string.pdf_status_date, dates(item.statusDate)))
                }.joinToString("\n")
                out += para(meta, pSmall, 3f)
                val verification = if (item.isVerified) s(R.string.pdf_verified, item.sourceCount)
                else s(R.string.pdf_unconfirmed, item.sourceCount)
                out += para(verification, pSmall, 2f)
                item.summary(am)?.let { sum -> out += para(sum, pBody, 4f) }
                if (e.citations.isNotEmpty()) {
                    out += para(s(R.string.pdf_sources, e.citations.joinToString(" ") { n -> "[$n]" }), pSmall, 3f)
                }
            }
        }
        if (r.citations.isNotEmpty()) {
            out += para(s(R.string.pdf_references), pH1, 20f, keep = true)
            for (c in r.citations) {
                val tier = when (c.tier) { 1 -> s(R.string.tier_official); 2 -> s(R.string.tier_agency); 3 -> s(R.string.tier_press); 4 -> s(R.string.tier_other); else -> s(R.string.tier_international) }
                out += para("[${c.number}]  ${c.publisher} ($tier) — ${c.title}\n${c.url}", pSmall, 4f, indent = 6f)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ layout

    private fun firstLines(l: StaticLayout): Float =
        (0 until minOf(2, l.lineCount)).sumOf { (l.getLineBottom(it) - l.getLineTop(it)).toDouble() }.toFloat()

    /**
     * Space paragraph [i] needs before it may start on the current page: its first two
     * lines, or — for a keep-with-next run such as section heading → entry heading →
     * title — the whole run plus the first two lines of what follows, so a heading is
     * never left alone at the foot of a page.
     */
    private fun neededAt(paras: List<Para>, i: Int, gap: Float): Float {
        var need = gap
        var j = i
        while (j < paras.size && paras[j].keepWithNext) {
            if (j > i) need += paras[j].spaceBefore
            need += paras[j].layout.height
            j++
        }
        val follow = paras.getOrNull(j) ?: return need
        if (j > i) need += follow.spaceBefore
        return need + firstLines(follow.layout)
    }

    private fun paginate(paras: List<Para>, firstPage: Int): Pair<List<Placed>, Int> {
        if (paras.isEmpty()) return emptyList<Placed>() to 0
        val placed = ArrayList<Placed>()
        var page = firstPage
        var y = margin
        for ((i, p) in paras.withIndex()) {
            val l = p.layout
            var gap = if (y == margin) 0f else p.spaceBefore
            if (y + neededAt(paras, i, gap) > bottom && y > margin) {
                page++; y = margin; gap = 0f
            }
            y += gap
            var line = 0
            while (line < l.lineCount) {
                var end = line
                while (end < l.lineCount && y + (l.getLineBottom(end) - l.getLineTop(line)) <= bottom) end++
                if (end == line) { // nothing fits on this page
                    if (y == margin) end = line + 1 else { page++; y = margin; continue }
                }
                placed += Placed(page, y, p, line, end)
                y += (l.getLineBottom(end - 1) - l.getLineTop(line)).toFloat()
                line = end
                if (line < l.lineCount) { page++; y = margin }
            }
        }
        return placed to (page - firstPage + 1)
    }

    private fun draw(c: Canvas, pl: Placed, entryPage: Map<Int, Int>) {
        val l = pl.para.layout
        val top = l.getLineTop(pl.start).toFloat()
        val bot = l.getLineBottom(pl.end - 1).toFloat()
        c.save()
        c.translate(margin + pl.para.indent, pl.y - top)
        c.clipRect(0f, top, l.width.toFloat(), bot)
        l.draw(c)
        c.restore()
        val target = pl.para.tocTarget
        if (target != null && pl.start == 0) {
            val num = entryPage[target]?.toString() ?: ""
            val baseline = pl.y + (l.getLineBaseline(0) - l.getLineTop(0))
            val p = Paint(l.paint).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(num, pageW - margin, baseline, p)
        }
    }

    private fun footer(c: Canvas, r: Report, page: Int, total: Int, am: Boolean) {
        val p = Paint(pSmall).apply { textAlign = Paint.Align.LEFT }
        val y = pageH - margin + 6f
        c.drawLine(margin, y - 14f, pageW - margin, y - 14f, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
        c.drawText("Aristophanes · ${r.period.label(am)}", margin, y, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(s(R.string.pdf_page, page, total), pageW - margin, y, p)
    }
}
