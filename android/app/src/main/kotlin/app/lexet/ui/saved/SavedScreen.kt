package app.lexet.ui.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import app.lexet.data.db.TagCount
import app.lexet.ui.EmptyState
import app.lexet.ui.ItemCard
import app.lexet.ui.appContainer
import app.lexet.ui.isAmharicUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SavedViewModel(private val c: AppContainer) : ViewModel() {
    /** null = bookmarks; otherwise a tag id. */
    val selectedTag = MutableStateFlow<Long?>(null)

    val items: StateFlow<List<ItemCardModel>> = selectedTag.flatMapLatest { tag ->
        c.repository.archive(
            if (tag == null) ArchiveFilter(jurisdiction = JurisdictionFilter.ALL, bookmarkedOnly = true)
            else ArchiveFilter(jurisdiction = JurisdictionFilter.ALL, tagId = tag)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagCount>> = c.repository.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun toggleBookmark(item: ItemCardModel) = viewModelScope.launch { c.repository.setBookmarked(item.id, !item.bookmarked) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(onOpen: (String) -> Unit) {
    val container = appContainer()
    val vm: SavedViewModel = viewModel { SavedViewModel(container) }
    val items by vm.items.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val selected by vm.selectedTag.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val labelsAm = isAmharicUi()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_saved)) })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = selected == null, onClick = { vm.selectedTag.value = null },
                    label = { Text(stringResource(R.string.filter_bookmarked)) })
            }
            items(tags, key = { it.id }) { t ->
                FilterChip(selected = selected == t.id, onClick = { vm.selectedTag.value = t.id },
                    label = { Text("${t.name} · ${t.count}") })
            }
        }
        if (items.isEmpty()) {
            EmptyState(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body))
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { item ->
                    ItemCard(item, settings.amharicFirst, labelsAm, settings.dateDisplay, container.taxonomy,
                        onClick = { onOpen(item.id) }, onToggleBookmark = { vm.toggleBookmark(item) })
                }
            }
        }
    }
}
