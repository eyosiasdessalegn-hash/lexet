# Aristophanes — Ethiopian legal intelligence

*(formerly LexET; the code keeps the `lexet` package names.)*

A native Android app and its backend that track Ethiopian legal developments —
proclamations (new and amended), draft bills before the House of Peoples'
Representatives, Council of Ministers regulations, ministerial and agency
directives, policy signals and legal news — from official, press and other
sources. Every update is shown, labelled **Verified** once at least three
independent sources (one official or ENA) report it, otherwise **Unconfirmed**.
A secondary stream of international and regional developments is mixed in at
roughly **10 Ethiopian items to 1 international item**.

In the app: a feed, an archive with Amharic-aware search plus **Search the web**
(laws as PDF, Ethiopian legal sites, LinkedIn posts, the whole web — opened in the
browser), **Share › Aristophanes** to save posts from LinkedIn, Telegram or any app
into the archive, notifications (verified updates, and optionally unconfirmed ones),
bookmarks, tags, and weekly/monthly/yearly PDF compilations. Beige-and-navy theme,
light and dark, in English and Amharic.

```
lexet/
├── backend/            Python 3.11+ · FastAPI · SQLAlchemy · httpx
│   ├── lexet/text/        Amharic normalisation, E.C.↔G.C. calendar, law-ID and name extraction, status/issuer/topic classification
│   ├── lexet/sources/     polite fetcher (robots.txt, ETag, rate limits) + adapters (WordPress, RSS, official listings, news articles) + source catalog
│   ├── lexet/pipeline/    extract → cluster → verify → summarise → international quota → publish
│   ├── lexet/static_feed.py  the feed the app reads, as static files (GitHub Pages) — also served by the API
│   ├── lexet/api/         HTTP API (feed, status page, assisted-ingestion admin endpoint)
│   ├── lexet/push.py      FCM data-only "tickles"
│   └── tests/             79 tests (text, adapters incl. aggregators, full pipeline, API, static feed) + demo data
├── android/            Kotlin 2.1 · Jetpack Compose (Material 3) · Room (FTS4) · WorkManager · DataStore · OkHttp
│   ├── domain/            pure-Kotlin core shared logic (17 tests) — normalisation, calendar, filters, digest, 10:1 feed mix, reports
│   ├── app/               the Android app
│   └── tools/sdkless/     verification that runs without the Android SDK
├── .github/workflows/  feed.yml: the server, run free every 30 min, published to GitHub Pages
│                       sources.yml: checks every source from GitHub's machines (robots, yield)
│                       build.yml: tests + APK build (Release with APKs on version tags)
├── shared/
│   ├── taxonomy.json        codes + English/Amharic labels used by both sides
│   └── ethiopic_vectors.json  normalisation test vectors both implementations must pass
└── docs/
    ├── ARCHITECTURE.md      components, sync & push design, performance, privacy, scaling, tech stack
    ├── VERIFICATION.md      the source tiers and verification pipeline, rule by rule
    ├── DATA_MODEL.md        server and device schemas, sync payload
    ├── SOURCES_AND_ROADMAP.md  what is monitored today and how coverage expands
    └── OPERATIONS.md        deploying, configuring push, assisted ingestion, legal/compliance notes
```

## Quick start

### Free, without a server (GitHub)

Push this repository to GitHub as a public repository, set *Settings › Pages › Source*
to **GitHub Actions**, and run *Actions › Feed* once. From then on GitHub crawls,
verifies and publishes the feed every 30 minutes at `https://<owner>.github.io/<repository>/`
(which also shows a status page). Details: `docs/OPERATIONS.md`.

### Backend

```bash
cd backend
pip install -e ".[dev]"          # add ,push for Firebase
pytest                            # 70 tests
lexet doctor                      # robots.txt status and yield of every configured source
lexet serve --port 8080           # API + feed + status page (OpenAPI docs at /docs)
lexet run                         # crawl/verify/publish scheduler (separate process)
lexet publish --out site          # one crawl cycle, then write the feed as static files
```

Or with Docker, from the repository root: `docker compose -f backend/docker-compose.yml up --build`.

### Android

1. Open `android/` in Android Studio Meerkat Feature Drop (2024.3.2) or newer (pinned set: AGP 8.10.1, Gradle 8.11.1, Kotlin 2.1.20, JDK 17+).
2. Optionally build in a feed address (`lexet.apiUrl` in `android/gradle.properties`);
   otherwise the app asks for one under *Settings › Server*. From the emulator, a backend on
   your computer is `http://10.0.2.2:8080/`.
3. Optional push: fill the four `lexet.fcm.*` values from a Firebase project (see `docs/OPERATIONS.md`).
   Without them the app relies on background polling plus immediate sync when connectivity returns.
4. Run the `app` configuration. Tests: `./gradlew :domain:test :app:testDebugUnitTest`
   (the app tests include a Robolectric run of every screen; the first run downloads the
   Robolectric Android runtime, about 200 MB).

## Getting an installable APK

The app builds to a standard APK you can download and install on any Android 8.0+ phone.

1. **GitHub (builds it for you):** push this folder to a GitHub repository. The included
   workflow (`.github/workflows/build.yml`) runs the tests and builds the APKs on every push —
   download them from *Actions → latest run → Artifacts*. Push a tag such as `v0.1.0` and the
   APKs are attached to a GitHub Release, which gives you a permanent download link to share.
2. **Android Studio:** *Build › Build App Bundle(s) / APK(s) › Build APK(s)*; the file appears
   in `android/app/build/outputs/apk/`.
3. **Command line** (with the Android SDK installed):
   `cd android && ./gradlew assembleRelease -Plexet.apiUrl=https://YOUR-FEED/`

Before installing on a phone:

- **The app needs a feed.** The free route is GitHub (above); APKs built there point at it
  automatically. Otherwise deploy the backend at an HTTPS address (`docs/OPERATIONS.md`).
  Any install can be pointed at a feed under *Settings › Server*.
- **Release APKs only use HTTPS** (plus the emulator's local address). The `-debug` APK also
  allows plain HTTP, for testing against a backend on a laptop on the same Wi-Fi; it installs
  alongside the release app.
- **Signing.** To publish updates people can install over earlier versions, create a release
  key once and keep it safe — Android rejects updates signed with a different key:
  `keytool -genkeypair -v -keystore lexet.jks -alias lexet -keyalg RSA -keysize 2048 -validity 10000`.
  Pass it with the `lexet.keystore*` properties, or as the `LEXET_KEYSTORE_*` secrets on GitHub.
  Without it, builds are signed with a throwaway debug key (fine for testing).
- **Installing:** open the APK on the phone; Android asks once to allow installs from the
  browser or file manager you used.

## What has been verified, and what has not

| Part | Status |
|---|---|
| Backend (extraction, calendar, verification, clustering, adapters, API, static feed) | 79 automated tests pass, including a simulated phone syncing the static feed (snapshot, segments, deletions, epoch reset) |
| Domain module (Amharic normalisation, calendar, filters, digest, feed mix, reports) | Compiled with Kotlin 2.1.20; 17 tests pass, including the vectors shared with the backend |
| App search/archive SQL | Generated queries run against an SQLite copy of the Room schema (FTS4): 15/15 pass; `ArchiveQueryTest` covers the same in the app module |
| Android app build | Compiles with no errors or warnings (AGP 8.10.1, Kotlin 2.1.20, compile/target SDK 36, min SDK 26). `assembleDebug` and `assembleRelease` (R8 minified, lint-vital checked) produce installable APKs: release ≈ 2 MB, debug ≈ 13 MB |
| App sync against the feed | `FeedSyncTest` syncs the app's real database against feed files exported by the backend from the demo data: snapshot on first install, only the changed segment afterwards, index-only when nothing changed, reset on a new epoch, a clear error for an address without a feed |
| Sharing and theme | `ShareTest` shares a LinkedIn post into the app and checks it is saved with its law number detected; `DarkThemeTest` renders the navy theme; saved posts survive a feed reset (`FeedSyncTest`) |
| App running end to end | `AppSmokeTest` (Robolectric, simulated Android 14) starts the real app with the backend's demo data and checks every screen: feed with verified/unconfirmed and international items; detail with both law-ID calendars and source counting reasons; Amharic and E.C. law-ID search; saved; reports; settings. Screenshots of each screen and of every compiled report page are written to `android/app/build/screenshots/`. The PDF container step is skipped there because Robolectric has no native PDF backend; phones do |
| On a physical phone | Not yet: the build machine has no emulator. Install the APK and try it against the demo backend (`docs/OPERATIONS.md` → *Local demo*) |
| Live crawling of Ethiopian sites | Not exercised from the build environment (network allowlist). `lexet doctor` checks every source after deployment |

**Important source finding:** `justice.gov.et` and `hopr.gov.et` refused automated
fetching when checked on 15 Oct 2026. The crawler never bypasses robots.txt; while
those two sites disallow it, their items come in through assisted ingestion
(`POST /v1/admin/observations` or `lexet submit`). Asking the Ministry of Justice and
the House for crawler permission or a data feed is the single most valuable next
step for coverage — see `docs/SOURCES_AND_ROADMAP.md`.
