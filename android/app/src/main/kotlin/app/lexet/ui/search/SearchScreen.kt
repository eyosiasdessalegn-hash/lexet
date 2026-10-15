package app.lexet.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lexet.AppContainer
import app.lexet.R
import app.lexet.data.AppSettings
import app.lexet.data.ArchiveFilter
import app.lexet.data.ItemCardModel
import app.lexet.data.JurisdictionFilter
import app.lexet.data.VerificationFilter
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.model.Urgency
import app.lexet.domain.notify.TypeFilter
import app.lexet.ui.EmptyState
import app.lexet.ui.ItemCard
import app.lexet.ui.SectionTitle
import app.lexet.ui.appContainer
import app.lexet.ui.isAmharicUi
import app.lexet.ui.topicLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(ArchiveFilter())

    val results: StateFlow<List<ItemCardModel>?> = filter.debounce(200)
        .flatMapLatest { c.repository.archive(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(f: (ArchiveFilter) -> ArchiveFilter) { filter.value = f(filter.value) }
    fun toggleBookmark(item: ItemCardModel) = viewModelScope.launch { c.repository.setBookmarked(item.id, !item.bookmarked) }
}

private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (String) -> Unit) {
    val container = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val labelsAm = isAmharicUi()
    var showFilters by remember { mutableStateOf(false) }
    val activeFilters = filter.copy(text = "") != ArchiveFilter()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter.text,
                onValueChange = { t -> vm.update { it.copy(text = t) } },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.text.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(text = "") } }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (activeFilters) Badge() }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.action_filters))
                }
            }
        }
        val list = results
        when {
            list == null -> {}
            list.isEmpty() -> EmptyState(stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body))
            else -> {
                Text(pluralStringResource(R.plurals.result_count, list.size, list.size),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { item ->
                        ItemCard(item, settings.amharicFirst, labelsAm, settings.dateDisplay, container.taxonomy,
                            onClick = { onOpen(item.id) }, onToggleBookmark = { vm.toggleBookmark(item) })
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FilterSheet(filter, container.taxonomy, labelsAm, onChange = { f -> vm.update { f } },
                onReset = { vm.update { ArchiveFilter(text = it.text) } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    f: ArchiveFilter,
    taxonomy: Taxonomy?,
    am: Boolean,
    onChange: (ArchiveFilter) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.action_filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset)) }
        }

        SectionTitle(stringResource(R.string.filter_jurisdiction))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(JurisdictionFilter.ETHIOPIA to R.string.filter_ethiopia, JurisdictionFilter.INTERNATIONAL to R.string.label_international,
                JurisdictionFilter.ALL to R.string.filter_all).forEach { (v, label) ->
                FilterChip(selected = f.jurisdiction == v, onClick = { onChange(f.copy(jurisdiction = v)) }, label = { Text(stringResource(label)) })
            }
        }

        SectionTitle(stringResource(R.string.filter_type))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TypeFilter.PROCLAMATION to "proclamation", TypeFilter.REGULATION to "regulation",
                TypeFilter.DIRECTIVE to "directive", TypeFilter.DRAFT_BILL to "draft_bill",
                TypeFilter.POLICY to "policy", TypeFilter.NOTICE to "notice").forEach { (t, code) ->
                FilterChip(selected = t in f.types, onClick = { onChange(f.copy(types = f.types.toggle(t))) },
                    label = { Text(taxonomy?.docType(code)?.text(am) ?: code) })
            }
        }

        SectionTitle(stringResource(R.string.filter_stage))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Urgency.entries.forEach { u ->
                FilterChip(selected = u in f.urgencies, onClick = { onChange(f.copy(urgencies = f.urgencies.toggle(u))) },
                    label = { Text(taxonomy?.urgency(u.code)?.text(am) ?: u.code) })
            }
        }

        SectionTitle(stringResource(R.string.filter_verification))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(VerificationFilter.ALL to R.string.filter_all, VerificationFilter.VERIFIED to R.string.badge_verified,
                VerificationFilter.UNCONFIRMED to R.string.badge_unconfirmed).forEach { (v, label) ->
                FilterChip(selected = f.verification == v, onClick = { onChange(f.copy(verification = v)) }, label = { Text(stringResource(label)) })
            }
        }

        SectionTitle(stringResource(R.string.filter_issuer))
        var open by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { open = true }) {
                Text(f.issuer?.let { taxonomy?.issuer(it)?.text(am) ?: it } ?: stringResource(R.string.filter_any_body))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.filter_any_body)) }, onClick = { onChange(f.copy(issuer = null)); open = false })
                taxonomy?.issuers?.forEach { i ->
                    DropdownMenuItem(text = { Text(i.text(am)) }, onClick = { onChange(f.copy(issuer = i.code)); open = false })
                }
            }
        }

        SectionTitle(stringResource(R.string.filter_topic))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            taxonomy?.topics?.forEach { t ->
                FilterChip(selected = t.code in f.topics, onClick = { onChange(f.copy(topics = f.topics.toggle(t.code))) },
                    label = { Text(taxonomy.topicLabel(t.code, am)) })
            }
        }

        SectionTitle(stringResource(R.string.filter_period))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to R.string.period_any, 7 to R.string.period_week, 30 to R.string.period_month, 365 to R.string.period_year)
                .forEach { (days, label) ->
                    FilterChip(selected = f.sinceDays == days, onClick = { onChange(f.copy(sinceDays = days)) }, label = { Text(stringResource(label)) })
                }
        }

        SectionTitle(stringResource(R.string.filter_mine))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = f.bookmarkedOnly, onClick = { onChange(f.copy(bookmarkedOnly = !f.bookmarkedOnly)) },
                label = { Text(stringResource(R.string.filter_bookmarked)) })
            FilterChip(selected = f.unreadOnly, onClick = { onChange(f.copy(unreadOnly = !f.unreadOnly)) },
                label = { Text(stringResource(R.string.filter_unread)) })
        }
        Spacer(Modifier.height(32.dp))
    }
}
