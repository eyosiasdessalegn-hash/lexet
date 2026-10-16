# Sources and roadmap

## Monitored today

Status from the **Check sources** workflow, run from GitHub's machines on 16 Oct 2026
(re-run it any time: *Actions › Check sources › Run workflow*).

| Source | Tier | Method | Status on 16 Oct 2026 |
|---|---|---|---|
| Ministry of Justice (`justice.gov.et`) | 1 | Official listing + PDF text | robots.txt unreachable from GitHub → not crawled. Use *Actions › Feed › Run workflow* to add items. |
| House of Peoples' Representatives (`hopr.gov.et`) | 1 | Official listing + PDF text | Same as MoJ. |
| Federal Negarit Gazeta | 1 | Detected on official sites; manual entry | No public feed. |
| Ethiopian Food and Drug Authority | 1 | Listing harvester | 403 to automated requests. |
| Ethiopian Intellectual Property Authority | 1 | WordPress | ✔ working |
| National Bank of Ethiopia | 1 | WordPress | ✔ reachable (few posts) |
| Ethiopian Capital Market Authority | 1 | WordPress → RSS | ✔ working |
| Ethiopian News Agency — English and Amharic | 2 | News list → articles | ✔ working (fixed: headline links) |
| The Reporter Ethiopia | 3 | WordPress | ✔ working |
| Capital Ethiopia, Addis Fortune | 3 | WordPress / RSS | ✔ working |
| Fana — English and Amharic | 3 | RSS | ✔ working |
| BBC News Amharic | 3 | RSS | ✔ working |
| Addis Standard | 3 | WordPress | robots.txt unreachable from GitHub → not crawled |
| Borkena | 4 | WordPress | ✔ working |
| Ethiopia Insight, Ethiopian Monitor | 4 | WordPress | ✔ reachable (few posts) |
| GDELT (worldwide news search) | 4 | API; each article keeps its own outlet | Rate-limited from GitHub's shared machines; retried each run |
| *Disabled:* Reporter Amharic, Walta, Ethiopian Press Agency (bot challenges); Chilot (404); DW Amharic (empty feed); Ethiopian Business Review (spam posts); Google News (robots.txt disallows) | | | Notes in `catalog.py` |
| UN News, AU, IGAD | 9 | RSS / list / WordPress | ✔ working (JURIST: 403) |

**Coverage policy (since 0.3.0).** Every legal update is published whatever the
source's reliability: tier 4 covers smaller outlets and aggregators, and Ethiopian
legal news that names no specific law is kept as its own single-source item.
Each item carries its label — *Verified* (three independent outlets, one of them
official or ENA) or *Unconfirmed* — so the reader decides how far to trust it.

**LinkedIn.** LinkedIn prohibits automated access without its express permission
(robots.txt notice; User Agreement), and its API does not offer search of public
posts, so it is not crawled. Users save posts themselves: *Share › Aristophanes* in
the LinkedIn app stores the post in their archive, and *Search › LinkedIn posts*
opens a web search of public posts in the browser.

### The robots.txt finding

Both priority official sites refused automated access when checked. The crawler
re-checks robots.txt every 24 hours and will start crawling automatically if
permission is granted. Options, in order of value:

1. **Ask for access.** A short letter to the Ministry of Justice and the House of
   Peoples' Representatives requesting either crawler permission for the
   `AristophanesBot` user agent (a robots.txt `Allow` rule) or a periodic data export
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
