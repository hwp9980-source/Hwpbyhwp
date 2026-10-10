package io.hwpbyhwp.viewer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.parser.DocumentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerState {
    data object Empty : ViewerState
    data class Loading(val name: String) : ViewerState
    data class Loaded(val name: String, val document: Document) : ViewerState
    data class Failed(val name: String, val message: String) : ViewerState
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Empty)
    val state: StateFlow<ViewerState> = _state

    private var currentUri: Uri? = null

    fun open(uri: Uri) {
        if (uri == currentUri && _state.value !is ViewerState.Failed) return
        currentUri = uri
        val app = getApplication<Application>()
        val name = displayName(uri)
        _state.value = ViewerState.Loading(name)
        viewModelScope.launch {
            _state.value = try {
                val doc = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("파일을 열 수 없습니다")
                    input.use { DocumentLoader.load(it, app.cacheDir) }
                }
                ViewerState.Loaded(name, doc)
            } catch (e: Exception) {
                ViewerState.Failed(name, e.message ?: e.javaClass.simpleName)
            }
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
}
