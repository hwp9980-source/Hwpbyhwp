package io.hwpbyhwp.viewer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.hwpbyhwp.core.layout.Page
import io.hwpbyhwp.core.layout.PageLayouter
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.parser.DocumentLoader
import io.hwpbyhwp.viewer.ui.AndroidTextMeasurer
import io.hwpbyhwp.viewer.ui.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerState {
    /** Laid out before but dropped to save memory; loads again when shown. */
    data object Unloaded : ViewerState
    data object Loading : ViewerState
    data class Loaded(val document: Document, val pages: List<Page>) : ViewerState
    data class Failed(val message: String) : ViewerState
}

/** One open document. [id] keys its tab and the view state (scroll, zoom) kept for it. */
data class DocumentTab(val id: Long, val uri: Uri, val name: String, val state: ViewerState)

data class TabsState(val tabs: List<DocumentTab> = emptyList(), val selectedId: Long? = null) {
    val selected: DocumentTab? get() = tabs.firstOrNull { it.id == selectedId }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TabsState())
    val state: StateFlow<TabsState> = _state

    private var nextId = 1L
    private val jobs = mutableMapOf<Long, Job>()
    /** Tab ids, most recently shown first: only the first [KEEP_LOADED] keep their laid-out pages. */
    private val recent = ArrayDeque<Long>()

    init {
        Fonts.init(application)
    }

    /** Opens each document in its own tab (or switches to it when already open) and shows the last one. */
    fun open(uris: List<Uri>) {
        var show: Long? = null
        for (uri in uris) {
            val existing = _state.value.tabs.firstOrNull { it.uri == uri }
            if (existing != null) {
                show = existing.id
                continue
            }
            keepAccess(uri)
            val tab = DocumentTab(nextId++, uri, displayName(uri), ViewerState.Unloaded)
            _state.update { it.copy(tabs = it.tabs + tab) }
            show = tab.id
        }
        show?.let(::select)
    }

    fun select(id: Long) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(selectedId = id) }
        recent.remove(id)
        recent.addFirst(id)
        if (tab.state is ViewerState.Unloaded || tab.state is ViewerState.Failed) load(tab)
        // Free the pages of documents not looked at recently; they reload when shown again.
        recent.drop(KEEP_LOADED).forEach { old ->
            jobs.remove(old)?.cancel()
            setTabState(old, ViewerState.Unloaded)
        }
    }

    fun close(id: Long) {
        jobs.remove(id)?.cancel()
        recent.remove(id)
        val tabs = _state.value.tabs
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val rest = tabs.filterNot { it.id == id }
        val wasSelected = _state.value.selectedId == id
        _state.update { it.copy(tabs = rest, selectedId = if (wasSelected) null else it.selectedId) }
        if (wasSelected && rest.isNotEmpty()) {
            // Show the most recently viewed remaining tab, else the neighbour.
            select(recent.firstOrNull() ?: rest[minOf(index, rest.lastIndex)].id)
        }
    }

    private fun load(tab: DocumentTab) {
        val app = getApplication<Application>()
        setTabState(tab.id, ViewerState.Loading)
        jobs[tab.id]?.cancel()
        jobs[tab.id] = viewModelScope.launch {
            val result = try {
                val doc = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(tab.uri)
                        ?: error("파일을 열 수 없습니다")
                    input.use { DocumentLoader.load(it, app.cacheDir) }
                }
                val pages = withContext(Dispatchers.Default) { PageLayouter(doc, AndroidTextMeasurer()).layout() }
                ViewerState.Loaded(doc, pages)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ViewerState.Failed(e.message ?: e.javaClass.simpleName)
            }
            setTabState(tab.id, result)
            jobs.remove(tab.id)
        }
    }

    private fun setTabState(id: Long, state: ViewerState) {
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) it.copy(state = state) else it }) }
    }

    /** A tab may be reloaded long after it was opened, so hold on to read access where the picker allows it. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "문서"
    }

    private companion object {
        /** Documents kept laid out at once; a 300-page document's pages take tens of MB. */
        const val KEEP_LOADED = 3
    }
}
