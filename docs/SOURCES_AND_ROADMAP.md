# Sources and roadmap

## Monitored today

| Source | Tier | Method | Interval | Status on 15 Oct 2026 |
|---|---|---|---|---|
| Ministry of Justice — laws, amendments, registered directives (`justice.gov.et`) | 1 | Official listing harvester + PDF text | 60 min | **Robots policy refused automated fetching.** Assisted ingestion until permission is granted. Listing path to be confirmed. |
| House of Peoples' Representatives — laws and drafts (`hopr.gov.et`) | 1 | Official listing harvester + PDF text | 60 min | **Robots policy refused automated fetching.** Assisted ingestion until permission is granted. |
| Federal Negarit Gazeta | 1 | Gazette PDFs detected on official sites; assisted entry | — | No public machine-readable feed exists; issues are detected by their header text. |
| Ethiopian Food and Drug Authority — policies, legislation, regulation documents | 1 | Listing harvester (`/doc-category/policies-legislation-and-regulation/`, paginated) | 6 h | Path confirmed via search results; to be checked with `lexet doctor`. |
| Ethiopian Intellectual Property Authority | 1 | WordPress | 6 h | WordPress site confirmed; IP gazette notices and directives. |
| Ethiopian News Agency — English and Amharic | 2 | News list → articles | 15 min | Article URL pattern to be confirmed with `lexet doctor`. |
| The Reporter Ethiopia | 3 | WordPress REST (RSS fallback) | 30 min | |
| Addis Standard | 3 | WordPress REST | 30 min | |
| Capital Ethiopia | 3 | WordPress REST | 60 min | |
| Addis Fortune | 3 | WordPress REST | 60 min | |
| Fana Media Corporation (English) | 3 | WordPress REST | 30 min | Moved to `fanamc.com`; copies of ENA are discounted automatically. |
| Chilot | 3 | WordPress REST | 3 h | Legal information site. |
| UN News — Law and crime prevention | 9 | RSS | 6 h | |
| JURIST | 9 | RSS | 6 h | |
| African Union — press releases | 9 | News list → articles | 6 h | |
| IGAD | 9 | WordPress | 12 h | |

Endpoint paths were set from public information; the build environment could
not reach these sites. After deployment run `lexet doctor` — it prints, per source,
whether robots.txt allows us, whether the site is reachable, and how many legal
items the adapter finds — and correct paths in `backend/lexet/sources/catalog.py`.

### The robots.txt finding

Both priority official sites refused automated access when checked. The crawler
re-checks robots.txt every 24 hours and will start crawling automatically if
permission is granted. Options, in order of value:

1. **Ask for access.** A short letter to the Ministry of Justice and the House of
   Peoples' Representatives requesting either crawler permission for the
   `LexETBot` user agent (a robots.txt `Allow` rule) or a periodic data export
   (CSV/JSON of law ID, title in both languages, type, date, PDF link). This also
   gives the project an official relationship that could later become an MoU.
2. **Assisted ingestion** (works now): a curator submits each new official item
   (`lexet submit …` or `POST /v1/admin/observations`, see OPERATIONS.md). One entry
   per new law is enough; the press and ENA supply the corroboration.
3. **Official social channels.** Most federal bodies, including the House and the
   Ministry of Justice, announce on Telegram and Facebook. Telegram's official
   Bot/MTProto API can read public channels within its terms (see roadmap).

## Roadmap for expanding Ethiopian coverage

### Phase 1 — production hardening (weeks 1–4)
- Confirm every listing path with `lexet doctor`; add per-source parsing tweaks only where the generic harvester misses entries.
- Request access from MoJ and HoPR; run assisted ingestion meanwhile.
- Add the Amharic editions of The Reporter and Addis Standard, and Fana Amharic (more tier-3 corroboration for Amharic-only reports).
- Curator console: a small web page over the admin endpoint (paste URL → preview extraction → submit).
- Monitoring: alert when a source has had no successful run for 24 h or its yield drops to zero.

### Phase 2 — regulators and the executive (months 2–3)
- National Bank of Ethiopia directives (banking, foreign exchange — high value for practitioners).
- Ministry of Revenues and Ethiopian Customs Commission (tax and customs directives).
- Ethiopian Capital Market Authority, Ethiopian Communications Authority, Ethiopian Investment Commission / Investment Board.
- Ministry of Mines (licensing directives), Ministry of Labour and Skills, Ministry of Trade and Regional Integration.
- Environmental Protection Authority, INSA, Ministry of Health.
- Office of the Prime Minister and Council of Ministers press releases (policy-initiation signals).
- Each is a catalog entry using an existing adapter type; most sites are WordPress or simple listings.

### Phase 3 — breadth and depth (months 3–6)
- **Telegram channels** of HoPR, MoJ, ENA and ministries through Telegram's official API.
- **OCR for legacy-font Amharic gazette PDFs** (Tesseract with the Amharic model), so official text becomes searchable.
- **Cross-language matching of unnumbered drafts**: an Amharic–English legal glossary plus embedding similarity, with curator confirmation below a confidence threshold.
- **Federal Supreme Court Cassation Division decisions** — binding interpretations of law; a natural second content type.
- **House of Federation** decisions (constitutional interpretation).
- Optional LLM summaries restricted to official text, labelled as machine-generated.

### Phase 4 — regional and specialist (6 months +)
- Regional state gazettes and councils (e.g. Oromia, Amhara, Addis Ababa City Administration), filtered by region.
- Sector-specific feeds (mining, energy, telecom) as presets in notification filters.
- UnifiedPush for phones without Google services; an iOS client using the same API.
- Data partnerships (MoU) with MoJ/HoPR for an official structured feed.
