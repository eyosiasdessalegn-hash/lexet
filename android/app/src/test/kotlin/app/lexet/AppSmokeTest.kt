package app.lexet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.lexet.data.db.ItemFtsEntity
import app.lexet.data.net.LexetJson
import app.lexet.data.net.SyncPage
import app.lexet.data.toDomain
import app.lexet.data.toEntity
import app.lexet.domain.report.CalendarSystem
import app.lexet.domain.report.PeriodKind
import app.lexet.domain.report.ReportBuilder
import app.lexet.domain.report.ReportPeriods
import app.lexet.domain.search.SearchIndex
import app.lexet.report.ReportPdfWriter
import app.lexet.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertTrue

/** The real app, with WorkManager in test mode and the backend's demo data preloaded. */
class TestLexetApp : LexetApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        super.onCreate()
        runBlocking { seed() }
    }

    private suspend fun seed() {
        val json = javaClass.classLoader!!.getResourceAsStream("demo_sync.json")!!.bufferedReader().readText()
        val page = LexetJson.decodeFromString(SyncPage.serializer(), json)
        val c = container
        c.db.withTransaction {
            page.items.forEachIndexed { i, dto ->
                val e = dto.toEntity(i + 1L)
                c.db.items().upsert(e)
                val d = SearchIndex.document(e.toDomain(), c.taxonomy)
                c.db.fts().put(ItemFtsEntity(e.ftsDocId, d.titles, d.summary, d.lawId, d.meta, d.body))
            }
        }
        c.settings.recordSyncSuccess(page.nextSince, System.currentTimeMillis())
    }
}

/**
 * Starts the app as a phone would (Application, database, settings, notification
 * channels, connectivity callback, navigation) on a simulated Android 14 runtime,
 * and walks every screen. Screenshots go to app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestLexetApp::class, qualifiers = "w411dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Draws the window in software (Robolectric produces no hardware frames for captureToImage). */
    private fun shot(name: String) {
        compose.waitForIdle()
        lateinit var bmp: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val shots = File("build/screenshots").apply { mkdirs() }

    private fun tab(label: String) = compose.onAllNodesWithText(label).onFirst().performClick()

    @Test
    fun feedShowsVerifiedAndUnconfirmedItemsWithTheInternationalStream() {
        waitForText("Digital Commerce Proclamation")
        compose.onNodeWithText("Rural Roads Loan Agreement Ratification Proclamation").assertExists()
        compose.onNodeWithText("Proclamation No. 1430/2026").assertExists()
        compose.onNodeWithText("Cosmetics Labelling Directive").assertExists()
        compose.onNodeWithText("AfCFTA states adopt digital trade protocol rules (DEMO)").assertExists()
        compose.onAllNodesWithText("Verified").onFirst().assertExists()
        compose.onAllNodesWithText("Unconfirmed").onFirst().assertExists()
        shot("01-feed")
    }

    @Test
    fun detailShowsBothLawIdsAndSourcesWithCountingReasons() {
        waitForText("Digital Commerce Proclamation")
        compose.onNodeWithText("Digital Commerce Proclamation").performClick()
        waitForText("Sources")
        compose.onNodeWithText("Proclamation No. 1431/2026  ·  አዋጅ ቁጥር 1431/2019").assertExists()
        compose.onNodeWithText("Not counted: syndicated from ENA", substring = true).assertExists()
        compose.onNodeWithText("Ministry of Trade and Regional Integration").assertExists()
        shot("02-detail")
    }

    @Test
    fun searchFindsAmharicAndLawIdsInBothCalendars() {
        waitForText("Digital Commerce Proclamation")
        tab("Search")
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("መረጃ")              // stored as "የመረጃ ደኅንነት አዋጅ"
        waitForText("የመረጃ ደኅንነት አዋጅ")
        compose.onNodeWithText("Laws (PDF)").assertExists()          // "Search the web" scopes
        compose.onNodeWithText("LinkedIn posts").assertExists()
        shot("03-search-amharic")
        field.performTextClearance()
        field.performTextInput("1430/2018")          // Ethiopian-calendar citation of No. 1430/2026
        waitForText("Rural Roads Loan Agreement Ratification Proclamation")
        compose.onAllNodes(hasText("Digital Commerce Proclamation")).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "law-ID search should not return other laws")
        }
    }

    @Test
    fun savedReportsAndSettingsScreensRender() {
        waitForText("Digital Commerce Proclamation")
        tab("Saved")
        waitForText("Nothing saved")
        tab("Reports")
        waitForText("Monthly")
        shot("04-reports")
        compose.onAllNodesWithText("Current")[1].performClick()   // this month
        waitForText("Compilation of Ethiopian legal developments")
        shot("05-report-view")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tab("Settings")
        waitForText("Notifications")
        compose.onNodeWithText("Ethiopian legal updates").assertExists()
        compose.onNodeWithText("Also unconfirmed updates").assertExists()
        shot("06-settings")
    }

    @Test
    fun pdfCompilationIsWritten() {
        waitForText("Digital Commerce Proclamation")
        val app = compose.activity.application as LexetApp
        val c = app.container
        val period = ReportPeriods.containing(LocalDate.of(2026, 9, 15), PeriodKind.YEAR, CalendarSystem.GREGORIAN)
        val items = runBlocking { c.repository.itemsInRange(period.start, period.endInclusive) }
        val report = ReportBuilder.build(items, period, Instant.now(), c.taxonomy)
        assertTrue(report.entryCount >= 5, "expected the demo items in the 2026 compilation")

        // Layout, pagination and drawing: every page rendered to an image (×2 scale).
        var pages = 0
        ReportPdfWriter(app, c.taxonomy).renderPages(report, amharicFirst = false) { i, total, draw ->
            val bmp = Bitmap.createBitmap(595 * 2, 842 * 2, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(Color.WHITE); scale(2f, 2f); draw(this) }
            File(shots, "07-pdf-page-${i + 1}-of-$total.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pages = total
        }
        assertTrue(pages >= 3, "cover, contents and at least one page of entries")

        // The PDF container itself needs the platform's native PDF backend, which a phone has
        // and Robolectric does not ("document is closed!" on the first page). Skip only that.
        val supported = runCatching {
            PdfDocument().run { try { finishPage(startPage(PdfDocument.PageInfo.Builder(10, 10, 1).create())) } finally { close() } }
        }.isSuccess
        Assume.assumeTrue("android.graphics.pdf is not implemented by this test runtime", supported)
        val file = File(shots, "compilation-2026.pdf")
        ReportPdfWriter(app, c.taxonomy).write(report, file, amharicFirst = false)
        assertTrue(file.length() > 2000 && file.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
    }
}
