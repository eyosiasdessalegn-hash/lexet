# Operations

## Free hosting on GitHub (no server)

`.github/workflows/feed.yml` runs the pipeline as a scheduled job and publishes the
feed to GitHub Pages; nothing else is needed. Free and unlimited for public repositories.

1. Push this repository to GitHub (public).
2. *Settings › Pages › Build and deployment › Source:* **GitHub Actions**.
3. *Actions › Feed › Run workflow* once (afterwards it runs every 30 minutes).
4. The feed and a status page are at `https://<owner>.github.io/<repository>/`. APKs
   built by `build.yml` use that address automatically; other installs enter it under
   *Settings › Server* (`<owner>.github.io/<repository>` is enough).
5. For APKs that install as updates over earlier ones, add the signing secrets
   (`LEXET_KEYSTORE_BASE64`, `LEXET_KEYSTORE_PASSWORD`, `LEXET_KEY_ALIAS`, `LEXET_KEY_PASSWORD`).

Notes:

- **Freshness:** a run every 30 minutes (GitHub may start it a few minutes late), plus up
  to 10 minutes of GitHub Pages caching. Push notifications, if configured, are sent
  only after the new files are live.
- **State:** the SQLite database is kept in the Actions cache between runs. If it is
  lost, the next run starts a new dataset (new epoch) and phones re-download.
- **Assisted ingestion:** *Actions › Feed › Run workflow* has a form (source, link,
  title, date, stage, gazette) for items from sites that refuse crawlers.
- **Push (optional):** repository variable `LEXET_FCM_PROJECT_ID` and secret
  `LEXET_FCM_CREDENTIALS_JSON` (the service-account key file's contents).
- GitHub pauses scheduled workflows in repositories without activity for 60 days;
  the workflow re-enables itself on every run to prevent that.

## Deploy on your own server

```bash
# from the repository root
export LEXET_ADMIN_TOKEN=$(openssl rand -hex 24)
docker compose -f backend/docker-compose.yml up -d --build
docker compose -f backend/docker-compose.yml exec api lexet doctor
```

Two containers share one SQLite database: `api` (sync API on port 8080) and
`worker` (crawl → verify → publish every minute). Put the API behind HTTPS (any
reverse proxy). For more than a few thousand phones, switch to PostgreSQL
(`LEXET_DATABASE_URL=postgresql+psycopg://…`, install with `pip install ".[postgres]"`)
and a CDN in front of `/feed/`.

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `LEXET_DATABASE_URL` | `sqlite:///./lexet.db` | Database |
| `LEXET_USER_AGENT` | `LexETBot/0.1 (+https://example.org/lexet-bot; …)` | **Set a real contact URL/email.** Site operators use it to reach you. |
| `LEXET_CRAWL_DELAY` | `2.0` | Seconds between requests to one host (robots.txt Crawl-delay wins if larger) |
| `LEXET_MIN_SOURCES`, `LEXET_REQUIRE_OFFICIAL_ANCHOR`, `LEXET_GAZETTE_CONFIRMS_IN_FORCE`, `LEXET_OFFICIAL_SELF_PUBLICATION_VERIFIES` | 3, true, true, false | Verification rule (docs/VERIFICATION.md) |
| `LEXET_INTL_RATIO` | `10` | Ethiopian items per international item |
| `LEXET_ADMIN_TOKEN` | unset | Enables assisted ingestion |
| `LEXET_FCM_PROJECT_ID`, `LEXET_FCM_CREDENTIALS`, `LEXET_FCM_TOPIC` | unset, unset, `lexet-et` | Push |

`LEXET_RESPECT_ROBOTS` exists for local testing against your own fixtures only; never disable it in production.

## Push notifications (optional)

1. Create a Firebase project; add an Android app with package `app.lexet`.
2. Also register `app.lexet.debug` if you want push in debug builds.
   From the project settings copy the app ID, API key, project ID and sender ID into
   `android/gradle.properties` (`lexet.fcm.appId`, `lexet.fcm.apiKey`,
   `lexet.fcm.projectId`, `lexet.fcm.senderId`). No `google-services.json` or Google
   Services Gradle plugin is needed; the app initialises Firebase itself, and only if
   these are set and the user keeps push on.
3. Create a service account with the *Firebase Cloud Messaging API Admin* role, download
   its JSON key to `backend/secrets/fcm.json`, and set `LEXET_FCM_PROJECT_ID` and
   `LEXET_FCM_CREDENTIALS=/secrets/fcm.json`. Install the backend with `[push]`.

Without push, phones sync on their schedule (30 min–6 h, user's choice), when the
app is opened, and immediately when connectivity returns.

## Assisted ingestion (curators)

For sources whose robots.txt disallows crawling (currently MoJ and HoPR), or for
anything the crawler missed. Submit the official item once; ENA and the press
provide the corroboration.

```bash
# CLI (on the server)
lexet submit --source moj_laws --primary --status in_force \
  --url "https://justice.gov.et/…/proclamation-1440.pdf" \
  --title "Commercial Registration Proclamation No. 1440/2026 የንግድ ምዝገባ አዋጅ ቁጥር 1440/2018" \
  --date 2026-09-01

# HTTP
curl -X POST https://api.example.org/v1/admin/observations \
  -H "Authorization: Bearer $LEXET_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"source_id":"hopr_laws","url":"https://hopr.gov.et/…","title":"…","text":"…",
       "published_at":"2026-09-01","is_primary_document":true,"status_hint":"tabled"}'
```

`status_hint` values: `policy_signal`, `draft`, `tabled`, `approved`, `in_force`,
`amended`, `repealed`. Use `negarit_manual` as the source for a Gazeta issue and add
`--gazette`.

## Local demo

```bash
cd backend
LEXET_DATABASE_URL=sqlite:///demo.db lexet replay tests/fixtures/demo.jsonl
LEXET_DATABASE_URL=sqlite:///demo.db lexet serve --port 8080
# Android emulator: enter http://10.0.2.2:8080/ under Settings › Server.
# Or publish the same data as static files: lexet export --out site
```

The demo file contains a fictional law moving from cabinet to gazette, reported the
way Ethiopian outlets report each stage. Law names and numbers in it are invented
and marked as demo data.

## Legal and compliance notes

These are points for counsel to confirm, not conclusions.

- **Crawling.** The crawler honours robots.txt and rate limits and identifies itself.
  It does not log in, bypass blocks or rotate identities. Where a site refuses
  automated access, use permission, an official feed or assisted entry.
- **Copyright.** Official legislative texts are excluded from protection under
  Article 5(b) of the Copyright and Neighbouring Rights Protection Proclamation
  No. 410/2004 (as amended), so official excerpts are synced for full-text search.
  News articles are protected: the app shows the headline, a short excerpt (280
  characters) and a link, and summaries are written from extracted facts rather than
  copied.
- **Personal data.** The app collects none. Push uses an anonymous topic
  subscription through Google; disclose this in the privacy policy, and review it
  against the Personal Data Protection Proclamation No. 1321/2024.
- **Not legal advice.** Every report carries a notice to check the official text in
  the Federal Negarit Gazeta before relying on any provision.

## Routine checks

- `lexet doctor` weekly, or after a source redesign.
- `GET /v1/sources` shows last success and last error per source (the app's
  *Monitored sources* screen reads it).
- Back up the database daily; the app can always re-download its archive.
