package app.lexet.ui.detail

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lexet.AppContainer
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.data.db.TagEntity
import app.lexet.data.db.UserStateEntity
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.SourceRef
import app.lexet.domain.model.rankOrMinus
import app.lexet.domain.text.EthiopianCalendar
import app.lexet.ui.Pill
import app.lexet.ui.SectionTitle
import app.lexet.ui.StatusPill
import app.lexet.ui.VerificationBadge
import app.lexet.ui.appContainer
import app.lexet.ui.docLabel
import app.lexet.ui.formatDate
import app.lexet.ui.isAmharicUi
import app.lexet.ui.issuerLabel
import app.lexet.ui.statusLabel
import app.lexet.ui.theme.LocalLexColors
import app.lexet.ui.topicLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

class DetailViewModel(private val c: AppContainer, val id: String) : ViewModel() {
    val item: StateFlow<LegalItem?> = c.repository.observeItem(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val user: StateFlow<UserStateEntity?> = c.repository.observeUserState(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val tags: StateFlow<List<TagEntity>> = c.repository.observeTagsFor(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun markRead() = viewModelScope.launch { c.repository.setRead(id, true); c.notifier.cancel(id) }
    fun setRead(read: Boolean) = viewModelScope.launch { c.repository.setRead(id, read) }
    fun setBookmarked(b: Boolean) = viewModelScope.launch { c.repository.setBookmarked(id, b) }
    fun addTag(name: String) = viewModelScope.launch { c.repository.addTag(id, name) }
    fun removeTag(tagId: Long) = viewModelScope.launch { c.repository.removeTag(id, tagId) }
    fun setNote(note: String) = viewModelScope.launch { c.repository.setNote(id, note) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: DetailViewModel = viewModel(key = "detail-$id") { DetailViewModel(container, id) }
    val item by vm.item.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val am = isAmharicUi()
    val tax = container.taxonomy
    val context = LocalContext.current
    var addingTag by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }

    LaunchedEffect(id) { vm.markRead() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(item?.let { it.lawId ?: it.title(settings.amharicFirst) } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            },
            actions = {
                val bookmarked = user?.bookmarked == true
                IconButton(onClick = { vm.setBookmarked(!bookmarked) }) {
                    Icon(if (bookmarked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(if (bookmarked) R.string.action_remove_bookmark else R.string.action_bookmark))
                }
                val read = user?.read == true
                IconButton(onClick = { vm.setRead(!read) }) {
                    Icon(if (read) Icons.Filled.MailOutline else Icons.Filled.Done,
                        stringResource(if (read) R.string.action_mark_unread else R.string.action_mark_read))
                }
                IconButton(onClick = { item?.let { context.startActivity(Intent.createChooser(shareIntent(it, settings.amharicFirst), null)) } }) {
                    Icon(Icons.Filled.Share, stringResource(R.string.action_share))
                }
            },
        )
        val it = item ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (it.jurisdiction == Jurisdiction.INTL) Pill(stringResource(R.string.label_international), LocalLexColors.current.upcoming)
                else StatusPill(it.verifiedStatus ?: it.status, tax, am)
                Spacer(Modifier.width(8.dp))
                Text(tax.docLabel(it.docType.code, am), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            listOfNotNull(it.lawId, it.lawIdAm).takeIf { ids -> ids.isNotEmpty() }?.let { ids ->
                Text(ids.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(it.title(settings.amharicFirst), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val secondary = if (settings.amharicFirst) it.titleEn else it.titleAm
            if (secondary != null && secondary != it.title(settings.amharicFirst)) {
                Text(secondary, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            } else if (it.jurisdiction == Jurisdiction.ET && (it.titleAm == null || it.titleEn == null)) {
                Text(stringResource(if (it.titleAm == null) R.string.no_amharic_title else R.string.no_english_title),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            if (it.jurisdiction == Jurisdiction.ET) VerificationBadge(it.verification, it.sourceCount, it.hasOfficialSource)

            if (it.reportedStatus != null && it.reportedStatus.rankOrMinus() > it.verifiedStatus.rankOrMinus() && it.verifiedStatus != null) {
                OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(stringResource(R.string.reported_later_stage, tax.statusLabel(it.reportedStatus, am)),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }

            SectionTitle(stringResource(R.string.section_summary))
            it.summary(settings.amharicFirst)?.let { s -> Text(s, style = MaterialTheme.typography.bodyMedium) }
            val other = if (settings.amharicFirst) it.summaryEn else it.summaryAm
            if (other != null && other != it.summary(settings.amharicFirst)) {
                Text(other, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }

            SectionTitle(stringResource(R.string.section_facts))
            Fact(stringResource(R.string.fact_issuer), tax.issuerLabel(it.issuer, am))
            Fact(stringResource(R.string.fact_initiator), tax.issuerLabel(it.initiator, am))
            Fact(stringResource(R.string.fact_status), it.verifiedStatus?.let { s -> tax.statusLabel(s, am) + " ✓" } ?: tax.statusLabel(it.status, am))
            Fact(stringResource(R.string.fact_first_reported), formatDate(it.eventDate, settings.dateDisplay, am).ifEmpty { null })
            Fact(stringResource(R.string.fact_status_date), formatDate(it.statusDate, settings.dateDisplay, am).ifEmpty { null })
            Fact(stringResource(R.string.fact_amends), it.amends?.let { k -> amendsLabel(k) })
            if (it.topics.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    it.topics.forEach { t -> AssistChip(onClick = {}, label = { Text(tax.topicLabel(t, am)) }) }
                }
            }

            SectionTitle(stringResource(R.string.section_tags))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { t ->
                    InputChip(selected = true, onClick = { vm.removeTag(t.id) }, label = { Text(t.name) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_remove_tag)) })
                }
                AssistChip(onClick = { addingTag = true }, label = { Text(stringResource(R.string.action_add_tag)) },
                    leadingIcon = { Icon(Icons.Filled.Add, null) })
            }
            Text(user?.note ?: stringResource(R.string.note_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clickable { editingNote = true })

            SectionTitle(stringResource(R.string.section_sources))
            Text(stringResource(R.string.sources_explainer), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            it.sources.sortedWith(compareBy<SourceRef>({ !it.counted }, { it.tier })).forEach { s -> SourceRow(s, am) }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (addingTag) TextDialog(stringResource(R.string.action_add_tag), "", singleLine = true, onDismiss = { addingTag = false }) { vm.addTag(it); addingTag = false }
    if (editingNote) TextDialog(stringResource(R.string.note_title), user?.note ?: "", singleLine = false, onDismiss = { editingNote = false }) { vm.setNote(it); editingNote = false }
}

private fun amendsLabel(key: String): String {
    val (type, number) = key.split(":").let { it.getOrNull(0) to it.getOrNull(1) }
    return when (type) {
        "proc" -> "Proclamation No. $number"
        "reg" -> "Regulation No. $number"
        else -> key
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun SourceRow(s: SourceRef, am: Boolean) {
    val uri = LocalUriHandler.current
    val tierLabel = stringResource(when (s.tier) {
        1 -> R.string.tier_official; 2 -> R.string.tier_agency; 3 -> R.string.tier_press; else -> R.string.tier_international
    })
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(tierLabel, if (s.tier == 1) LocalLexColors.current.official else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
        val date = s.publishedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
        Text(
            listOfNotNull(
                date?.let { "${EthiopianCalendar.formatGregorian(it, am)} · ${EthiopianCalendar.format(it, am)}" },
                if (s.counted) stringResource(R.string.source_counted) else stringResource(R.string.source_not_counted, s.reason),
                if (s.gazette) stringResource(R.string.source_gazette) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (s.counted) LocalLexColors.current.verified else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val excerpt = s.excerpt
        if (expanded && !excerpt.isNullOrBlank()) {
            Text(excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 30, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
        TextButton(onClick = { runCatching { uri.openUri(s.url) } }, modifier = Modifier.padding(top = 2.dp)) {
            Text(stringResource(R.string.action_open_source))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        HorizontalDivider()
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine, minLines = if (singleLine) 1 else 3) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun shareIntent(item: LegalItem, amharicFirst: Boolean): Intent {
    val text = buildString {
        listOfNotNull(item.lawId, item.lawIdAm).takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
        appendLine(item.title(amharicFirst))
        item.summary(amharicFirst)?.let { appendLine(); appendLine(it) }
        if (item.sources.isNotEmpty()) {
            appendLine()
            item.sources.filter { it.counted }.forEach { appendLine("• ${it.name}: ${it.url}") }
        }
        appendLine()
        append("— Aristophanes")
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, item.lawId ?: item.title(amharicFirst))
        putExtra(Intent.EXTRA_TEXT, text)
    }
}
