package app.lexet.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lexet.AppContainer
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.data.DateDisplay
import app.lexet.data.SyncState
import app.lexet.data.ThemeMode
import app.lexet.data.TitleLanguage
import app.lexet.data.net.SourceInfoDto
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.model.Urgency
import app.lexet.domain.notify.DigestMode
import app.lexet.domain.notify.NotificationFilters
import app.lexet.domain.notify.TypeFilter
import app.lexet.domain.report.CalendarSystem
import app.lexet.sync.Push
import app.lexet.sync.SyncScheduler
import app.lexet.ui.SectionTitle
import app.lexet.ui.appContainer
import app.lexet.ui.isAmharicUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(private val c: AppContainer, private val app: Application) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val sync: StateFlow<SyncState> = c.settings.syncState.stateIn(viewModelScope, SharingStarted.Eagerly, SyncState())

    fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(f) }
    fun filters(f: (NotificationFilters) -> NotificationFilters) = update { it.copy(notifications = f(it.notifications)) }
    fun syncNow() = SyncScheduler.syncNow(app, expedited = true)

    /** A different server means a different archive: wipe and re-download. */
    fun changeServer(url: String) = viewModelScope.launch {
        val clean = normaliseServer(url)
        c.settings.update { it.copy(apiUrl = clean) }
        c.settings.resetSync()
        c.repository.resetArchive()
        SyncScheduler.syncNow(app, expedited = true)
    }
}

private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v

/** Accepts "name.github.io/lexet" as well as full addresses; https is assumed when no scheme is given. */
internal fun normaliseServer(input: String): String {
    val t = input.trim().removeSuffix("/feed/index.json").trimEnd('/')
    if (t.isEmpty()) return ""
    val withScheme = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    return "$withScheme/"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onSources: () -> Unit) {
    val container = appContainer()
    val app = LocalContext.current.applicationContext as Application
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container, app) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val am = isAmharicUi()
    val tax = container.taxonomy
    val n = s.notifications
    var editKeywords by remember { mutableStateOf(false) }
    var editIssuers by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            SectionTitle(stringResource(R.string.set_notifications))
            SwitchRow(stringResource(R.string.set_notify_verified), stringResource(R.string.set_notify_verified_desc), n.enabled) { v -> vm.filters { it.copy(enabled = v) } }
            SwitchRow(stringResource(R.string.set_lock_screen), stringResource(R.string.set_lock_screen_desc), n.showOnLockScreen) { v -> vm.filters { it.copy(showOnLockScreen = v) } }

            Label(stringResource(R.string.set_digest))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DigestMode.INSTANT to R.string.digest_instant, DigestMode.HOURLY to R.string.digest_hourly, DigestMode.DAILY to R.string.digest_daily)
                    .forEach { (mode, label) ->
                        FilterChip(selected = n.digest == mode, onClick = { vm.filters { it.copy(digest = mode) } }, label = { Text(stringResource(label)) })
                    }
            }

            Label(stringResource(R.string.set_filter_types))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TypeFilter.PROCLAMATION to "proclamation", TypeFilter.REGULATION to "regulation",
                    TypeFilter.DIRECTIVE to "directive", TypeFilter.DRAFT_BILL to "draft_bill", TypeFilter.POLICY to "policy",
                    TypeFilter.NOTICE to "notice").forEach { (t, code) ->
                    FilterChip(selected = t in n.types, onClick = { vm.filters { it.copy(types = it.types.toggle(t)) } },
                        label = { Text(tax?.docType(code)?.text(am) ?: code) })
                }
            }
            Hint(stringResource(R.string.set_empty_means_all))

            Label(stringResource(R.string.set_filter_urgency))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Urgency.UPCOMING, Urgency.IMMINENT, Urgency.IN_FORCE).forEach { u ->
                    FilterChip(selected = u in n.urgencies, onClick = { vm.filters { it.copy(urgencies = it.urgencies.toggle(u)) } },
                        label = { Text(tax?.urgency(u.code)?.text(am) ?: u.code) })
                }
            }

            Label(stringResource(R.string.set_filter_topics))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tax?.topics?.forEach { t ->
                    FilterChip(selected = t.code in n.topics, onClick = { vm.filters { it.copy(topics = it.topics.toggle(t.code)) } },
                        label = { Text(t.text(am)) })
                }
            }

            ValueRow(stringResource(R.string.set_filter_issuers),
                if (n.issuers.isEmpty()) stringResource(R.string.filter_any_body) else n.issuers.joinToString { code -> tax?.issuer(code)?.text(am) ?: code }) { editIssuers = true }
            ValueRow(stringResource(R.string.set_filter_keywords),
                if (n.keywords.isEmpty()) stringResource(R.string.set_none) else n.keywords.joinToString(", ")) { editKeywords = true }

            SectionTitle(stringResource(R.string.set_display))
            Label(stringResource(R.string.set_title_language))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.titleLanguage == TitleLanguage.AMHARIC_FIRST, onClick = { vm.update { it.copy(titleLanguage = TitleLanguage.AMHARIC_FIRST) } },
                    label = { Text(stringResource(R.string.lang_amharic_first)) })
                FilterChip(selected = s.titleLanguage == TitleLanguage.ENGLISH_FIRST, onClick = { vm.update { it.copy(titleLanguage = TitleLanguage.ENGLISH_FIRST) } },
                    label = { Text(stringResource(R.string.lang_english_first)) })
            }
            Hint(stringResource(R.string.set_ui_language_hint))
            Label(stringResource(R.string.set_dates))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DateDisplay.BOTH to R.string.dates_both, DateDisplay.GREGORIAN to R.string.dates_gregorian, DateDisplay.ETHIOPIAN to R.string.dates_ethiopian)
                    .forEach { (d, label) ->
                        FilterChip(selected = s.dateDisplay == d, onClick = { vm.update { it.copy(dateDisplay = d) } }, label = { Text(stringResource(label)) })
                    }
            }
            Label(stringResource(R.string.set_theme))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                    .forEach { (t, label) ->
                        FilterChip(selected = s.theme == t, onClick = { vm.update { it.copy(theme = t) } }, label = { Text(stringResource(label)) })
                    }
            }

            SectionTitle(stringResource(R.string.nav_reports))
            SwitchRow(stringResource(R.string.set_auto_reports), stringResource(R.string.set_auto_reports_desc), s.autoReports) { v -> vm.update { it.copy(autoReports = v) } }
            SwitchRow(stringResource(R.string.set_notify_reports), null, s.notifyReports) { v -> vm.update { it.copy(notifyReports = v) } }
            Label(stringResource(R.string.set_report_calendar))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.reportCalendar == CalendarSystem.GREGORIAN, onClick = { vm.update { it.copy(reportCalendar = CalendarSystem.GREGORIAN) } },
                    label = { Text(stringResource(R.string.dates_gregorian)) })
                FilterChip(selected = s.reportCalendar == CalendarSystem.ETHIOPIAN, onClick = { vm.update { it.copy(reportCalendar = CalendarSystem.ETHIOPIAN) } },
                    label = { Text(stringResource(R.string.dates_ethiopian)) })
            }

            SectionTitle(stringResource(R.string.set_sync))
            if (Push.configured) {
                SwitchRow(stringResource(R.string.set_push), stringResource(R.string.set_push_desc), s.pushEnabled) { v -> vm.update { it.copy(pushEnabled = v) } }
            } else {
                Hint(stringResource(R.string.set_push_unavailable))
            }
            Label(stringResource(R.string.set_interval))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 180, 360).forEach { m ->
                    FilterChip(selected = s.syncIntervalMinutes == m, onClick = { vm.update { it.copy(syncIntervalMinutes = m) } },
                        label = { Text(if (m < 60) stringResource(R.string.minutes_n, m) else stringResource(R.string.hours_n, m / 60)) })
                }
            }
            SwitchRow(stringResource(R.string.set_unmetered), stringResource(R.string.set_unmetered_desc), s.unmeteredOnly) { v -> vm.update { it.copy(unmeteredOnly = v) } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    if (sync.lastSuccessAt > 0) stringResource(R.string.set_last_sync, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(sync.lastSuccessAt)))
                    else stringResource(R.string.sync_never),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.syncNow() }) { Text(stringResource(R.string.action_sync_now)) }
            }
            sync.lastError?.let { Hint(stringResource(R.string.set_last_error, it)) }

            SectionTitle(stringResource(R.string.set_sources_privacy))
            ValueRow(stringResource(R.string.set_sources), stringResource(R.string.set_sources_desc)) { onSources() }
            ValueRow(stringResource(R.string.set_server), s.apiUrl.ifBlank { stringResource(R.string.server_not_set) }) { editServer = true }
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.verification_rule_text), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }

    if (editKeywords) KeywordsDialog(n.keywords, onDismiss = { editKeywords = false }) { kws -> vm.filters { it.copy(keywords = kws) }; editKeywords = false }
    if (editIssuers) IssuersDialog(tax, am, n.issuers, onDismiss = { editIssuers = false }) { set -> vm.filters { it.copy(issuers = set) }; editIssuers = false }
    if (editServer) ServerDialog(s.apiUrl, onDismiss = { editServer = false }) { url -> vm.changeServer(url); editServer = false }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeywordsDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_filter_keywords)) },
        text = {
            Column {
                Text(stringResource(R.string.keywords_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 4, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun IssuersDialog(tax: Taxonomy?, am: Boolean, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_filter_issuers)) },
        text = {
            LazyColumn {
                items(tax?.issuers.orEmpty().filter { it.code != "OTHER" }) { i ->
                    Row(Modifier.fillMaxWidth().clickable { selected = selected.toggle(i.code) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i.code in selected, onCheckedChange = { selected = selected.toggle(i.code) })
                        Text(i.text(am), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ServerDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_server)) },
        text = {
            Column {
                Text(stringResource(R.string.server_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("https://yourname.github.io/lexet/") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---------------------------------------------------------------------------- sources

class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val sources = MutableStateFlow<List<SourceInfoDto>?>(null)
    val error = MutableStateFlow<String?>(null)
    fun load() = viewModelScope.launch {
        runCatching { c.api.sources() }.onSuccess { sources.value = it; error.value = null }.onFailure { error.value = it.message }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: SourcesViewModel = viewModel { SourcesViewModel(container) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.set_sources)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } })
        error?.let { Text(stringResource(R.string.sources_error, it), modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item { Text(stringResource(R.string.sources_intro), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            items(sources.orEmpty()) { src ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(src.name, style = MaterialTheme.typography.titleSmall)
                    val tier = stringResource(when (src.tier) { 1 -> R.string.tier_official; 2 -> R.string.tier_agency; 3 -> R.string.tier_press; else -> R.string.tier_international })
                    val robots = when (src.robotsState) {
                        "allowed" -> stringResource(R.string.robots_allowed)
                        "disallowed" -> stringResource(R.string.robots_disallowed)
                        else -> stringResource(R.string.robots_unknown)
                    }
                    Text("$tier · $robots", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
