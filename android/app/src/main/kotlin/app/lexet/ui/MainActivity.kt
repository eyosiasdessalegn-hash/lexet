package app.lexet.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lexet.domain.clip.Clip
import app.lexet.domain.clip.ClipParser
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lexet.LexetApp
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.notify.Notifier
import app.lexet.ui.detail.DetailScreen
import app.lexet.ui.feed.FeedScreen
import app.lexet.ui.reports.ReportViewScreen
import app.lexet.ui.reports.ReportsScreen
import app.lexet.ui.saved.SavedScreen
import app.lexet.ui.search.SearchScreen
import app.lexet.ui.settings.SettingsScreen
import app.lexet.ui.settings.SourcesScreen
import app.lexet.ui.theme.LexetTheme

class MainActivity : ComponentActivity() {
    /** Destination requested by a notification tap, consumed once by the nav host. */
    private var pending by mutableStateOf<String?>(null)

    /** A post shared into the app (LinkedIn, Telegram, a browser…) waiting to be saved. */
    private var shared by mutableStateOf<Clip?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pending = routeFor(intent)
        shared = clipFrom(intent)
        val container = (application as LexetApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            LexetTheme(settings.theme) {
                AppRoot(pending, onConsumed = { pending = null }, shared = shared, onShareDone = { shared = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pending = it }
        clipFrom(intent)?.let { shared = it }
    }

    private fun clipFrom(intent: Intent?): Clip? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return ClipParser.parse(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    private fun routeFor(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(Notifier.EXTRA_ITEM_ID)?.let { return "item/$it" }
        return when (val d = intent.getStringExtra(Notifier.EXTRA_DESTINATION)) {
            null -> null
            "feed" -> "feed"
            else -> if (d.startsWith("report:")) "report/${d.removePrefix("report:")}" else null
        }
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("feed", R.string.nav_feed, Icons.Filled.Home),
    Tab("search", R.string.nav_search, Icons.Filled.Search),
    Tab("saved", R.string.nav_saved, Icons.Filled.Favorite),
    Tab("reports", R.string.nav_reports, Icons.Filled.DateRange),
    Tab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
private fun AppRoot(pending: String?, onConsumed: () -> Unit, shared: Clip?, onShareDone: () -> Unit) {
    val nav = rememberNavController()
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.clip_saved_toast)


    val unread by container.repository.unreadCount().collectAsStateWithLifecycle(initialValue = 0)
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }


    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = {
                                if (tab.route == "feed" && unread > 0) {
                                    BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else Icon(tab.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Top app bars handle the status bar; the bottom bar (or this padding) handles the navigation bar.
        Box(Modifier.fillMaxSize().padding(padding).then(if (showBar) Modifier else Modifier.navigationBarsPadding())) {
            NavHost(nav, startDestination = "feed") {
                composable("feed") { FeedScreen(onOpen = { nav.navigate("item/$it") }, onOpenSettings = { nav.switchTab("settings") }) }
                composable("search") { SearchScreen(onOpen = { nav.navigate("item/$it") }) }
                composable("saved") { SavedScreen(onOpen = { nav.navigate("item/$it") }) }
                composable("reports") { ReportsScreen(onView = { nav.navigate("report/$it") }) }
                composable("settings") { SettingsScreen(onSources = { nav.navigate("sources") }) }
                composable("item/{id}") { entry ->
                    DetailScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                }
                composable("report/{key}") { entry ->
                    ReportViewScreen(entry.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() },
                        onOpenItem = { nav.navigate("item/$it") })
                }
                composable("sources") { SourcesScreen(onBack = { nav.popBackStack() }) }
                composable("share") {
                    val clip = shared
                    if (clip == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else SaveClipScreen(clip, onCancel = { onShareDone(); nav.popBackStack() }) { title, text ->
                        scope.launch {
                            val final = ClipParser.parse(listOfNotNull(text, clip.url).joinToString("\n"), title.ifBlank { null })
                            val id = container.repository.saveClip(final)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                                onShareDone()
                                nav.navigate("item/$id") { popUpTo("share") { inclusive = true } }
                            }
                        }
                    }
                }
            }
            // Requests from outside (notification tap, share sheet). Run here, after the NavHost has set its
            // graph: the Scaffold composes this content later than the code above it.
            LaunchedEffect(pending) {
                pending?.let { nav.navigate(it) { launchSingleTop = true }; onConsumed() }
            }
            LaunchedEffect(shared) {
                if (shared != null) nav.navigate("share") { launchSingleTop = true }
            }
        }
    }
}

/** "Save to Aristophanes": title and text are editable; pasting the post's text makes it searchable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveClipScreen(clip: Clip, onCancel: () -> Unit, onSave: (title: String, text: String) -> Unit) {
    var title by remember(clip) { mutableStateOf(clip.title) }
    var text by remember(clip) { mutableStateOf(clip.text) }
    val detected = remember(title, text) { ClipParser.parse(listOfNotNull(text, clip.url).joinToString("\n"), title).lawIdText }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.clip_dialog_title)) },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) } },
            actions = { TextButton(onClick = { onSave(title, text) }) { Text(stringResource(R.string.action_save)) } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clip_from, clip.platform), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            clip.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.clip_title)) },
                maxLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.clip_text)) },
                placeholder = { Text(stringResource(R.string.clip_text_hint)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
            detected?.let {
                Text(stringResource(R.string.clip_detected, it), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onSave(title, text) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
