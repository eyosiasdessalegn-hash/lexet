package app.lexet

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lexet.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

internal fun <A : androidx.activity.ComponentActivity> AndroidComposeTestRule<*, A>.screenshot(name: String) {
    waitForIdle()
    runOnUiThread {
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

internal fun AndroidComposeTestRule<*, *>.waitForText(text: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

/** A LinkedIn post shared into the app ("Save to Aristophanes"). Screenshots in app/build/screenshots/. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestLexetApp::class, qualifiers = "w411dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareTest {
    private val share = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Explainer on the new Investment Proclamation No. 1180/2020 and its incentives for mining.\n" +
            "https://www.linkedin.com/posts/abebe-kebede_investment-activity-7311-abcd")

    @get:Rule
    val compose = AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(share)) { rule ->
        var a: MainActivity? = null
        rule.scenario.onActivity { a = it }
        a!!
    }

    @Test
    fun linkedInPostIsSavedSearchableAndLabelled() {
        compose.waitForText("Save to your archive")
        compose.onNodeWithText("From LinkedIn").assertExists()
        compose.onNodeWithText("Detected: Proclamation No. 1180/2020").assertExists()
        compose.screenshot("08-share-dialog")
        compose.onAllNodesWithText("Save").onFirst().performClick()
        compose.waitForText("Saved by you from LinkedIn")
        compose.onAllNodesWithText("Proclamation No. 1180/2020").onFirst().assertExists()
        compose.screenshot("09-saved-post")
    }
}

/** The beige-and-navy theme in dark mode. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestLexetApp::class, qualifiers = "w411dp-h1400dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkThemeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun feedRendersInDarkMode() {
        compose.waitForText("Digital Commerce Proclamation")
        compose.screenshot("10-feed-dark")
    }
}
