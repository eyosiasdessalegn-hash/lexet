package app.lexet.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lexet.data.db.LexetDatabase
import app.lexet.data.net.FeedUnavailableException
import app.lexet.data.net.LexetApi
import app.lexet.domain.model.Taxonomy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The app's sync against feed files produced by the real backend (`lexet export`
 * over the demo data, in two stages: test resources feed/v1 and feed/v2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FeedSyncTest {
    private val server = MockWebServer()
    private val requested = mutableListOf<String>()
    @Volatile private var site: Map<String, String> = emptyMap()

    private lateinit var db: LexetDatabase
    private lateinit var repo: ItemRepository
    private lateinit var settings: SettingsStore

    private fun load(version: String): Map<String, String> {
        val names = listOf("index.json", "sources.json") +
            javaClass.classLoader!!.getResource("feed/$version")!!.let { java.io.File(it.toURI()).list()!!.toList() }
        return names.distinct().associate { n ->
            "/feed/$n" to javaClass.classLoader!!.getResourceAsStream("feed/$version/$n")!!.bufferedReader().readText()
        }
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                requested += path
                return site[path]?.let { MockResponse().setBody(it).setHeader("Content-Type", "application/json") }
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, LexetDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(ctx)
        val taxonomy = ctx.assets.open("taxonomy.json").bufferedReader().use { Taxonomy.parse(it.readText()) }
        val api = LexetApi(OkHttpClient(), baseUrl = { server.url("/").toString() })
        repo = ItemRepository(db, api, settings) { taxonomy }
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun titles() = runBlocking { db.items().ethiopianInRange(0, Long.MAX_VALUE / 2) }.map { it.titleEn ?: it.titleAm }

    @Test fun snapshotThenSegmentsThenEpochResetThenMissingFeed() = runBlocking {
        // 1. New install: index + one snapshot.
        site = load("v1")
        val first = repo.sync()
        assertEquals(listOf("/feed/index.json", "/feed/snapshot-efbc81a1d0b0.json"), requested)
        assertEquals(3, first.items)
        assertEquals(3, db.items().count())
        assertTrue(first.events.isEmpty(), "the first download is the archive, not news")
        assertEquals(6, settings.currentSync().since)

        // 2. Nothing new: only the index is fetched.
        requested.clear()
        assertTrue(repo.sync().notModified)
        assertEquals(listOf("/feed/index.json"), requested)

        // 3. New publication: only the changed segment, never the snapshot.
        site = load("v2")
        requested.clear()
        val second = repo.sync()
        assertEquals(listOf("/feed/index.json", "/feed/seg-0-1bec61ac8833.json"), requested)
        assertEquals(7, db.items().count())                 // 6 Ethiopian + 1 international
        val roads = db.items().ethiopianInRange(0, Long.MAX_VALUE / 2).first { it.lawId == "Proclamation No. 1430/2026" }
        assertEquals("in_force", roads.status)
        assertTrue(second.events.isNotEmpty(), "the roads law entering into force is news")
        assertTrue("Cosmetics Labelling Directive" in titles())
        assertEquals(18, settings.currentSync().since)

        // 4. The feed was rebuilt (new epoch): start again from its snapshot.
        site = site.mapValues { (k, v) -> if (k == "/feed/index.json") v.replace(Regex("\"epoch\":\\d+"), "\"epoch\":7") else v }
        db.items().upsert(db.items().get(roads.id)!!.copy(id = "stale-item", ftsDocId = 9_999))
        val clipId = repo.saveClip(app.lexet.domain.clip.ClipParser.parse("My notes https://www.linkedin.com/posts/x", null))
        requested.clear()
        repo.sync()
        assertEquals(listOf("/feed/index.json", "/feed/snapshot-1bec61ac8833.json"), requested)
        assertEquals(null, db.items().get("stale-item"))
        assertTrue(db.items().get(clipId) != null, "the user's saved posts survive a feed reset")
        assertEquals(8, db.items().count())

        // 5. An address without a feed says so plainly.
        site = emptyMap()
        val e = assertFailsWith<FeedUnavailableException> { repo.sync() }
        assertTrue("No Aristophanes feed" in e.message!!)
    }
}
