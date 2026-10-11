package io.hwpbyhwp.viewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hwpbyhwp.viewer.ui.Fonts
import io.hwpbyhwp.viewer.ui.PageView
import io.hwpbyhwp.viewer.ui.ReadingView

class MainActivity : ComponentActivity() {

    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        enableEdgeToEdge()
        // On recreation (rotation) the view model still holds the tabs; don't open the intent again.
        if (savedInstanceState == null) intent?.documentUris()?.let(viewModel::open)
        setContent {
            MaterialTheme {
                ViewerScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.documentUris()?.let(viewModel::open)
    }

    private fun Intent.documentUris(): List<Uri>? = when (action) {
        Intent.ACTION_VIEW -> data?.let { listOf(it) }
        Intent.ACTION_SEND ->
            (if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { listOf(it) }
        Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        else -> null
    }?.takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(viewModel: ViewerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Many senders label HWP files as octet-stream, so accept anything and let the loader sniff the format.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.open(uris)
    }
    val openFile = { picker.launch(arrayOf("*/*")) }
    var readingMode by rememberSaveable { mutableStateOf(false) }
    // Scroll position and zoom of each tab survive switching between tabs.
    val viewStates = rememberSaveableStateHolder()
    val selected = state.selected

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(selected?.name ?: "한글 뷰어", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (selected?.state is ViewerState.Loaded) {
                            TextButton(onClick = { readingMode = !readingMode }) { Text(if (readingMode) "쪽 보기" else "읽기 모드") }
                        }
                        TextButton(onClick = openFile) { Text("열기") }
                    },
                )
                if (state.tabs.size > 1) {
                    val index = state.tabs.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0)
                    ScrollableTabRow(selectedTabIndex = index, edgePadding = 8.dp) {
                        state.tabs.forEach { tab ->
                            Tab(
                                selected = tab.id == state.selectedId,
                                onClick = { viewModel.select(tab.id) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                                        IconButton(
                                            onClick = {
                                                viewStates.removeState(tab.id)
                                                viewModel.close(tab.id)
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) { Text("✕") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (selected == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("HWP·HWPX 문서를 열어 보세요")
                    Text("여러 파일을 한꺼번에 고를 수 있습니다", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = openFile) { Text("파일 열기") }
                }
            } else when (val s = selected.state) {
                ViewerState.Unloaded, ViewerState.Loading -> CircularProgressIndicator()
                is ViewerState.Failed -> Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(s.message)
                    Button(onClick = { viewModel.select(selected.id) }) { Text("다시 시도") }
                    TextButton(onClick = { viewModel.close(selected.id) }) { Text("탭 닫기") }
                }
                is ViewerState.Loaded -> viewStates.SaveableStateProvider(selected.id) {
                    if (readingMode) ReadingView(s.document, Modifier.fillMaxSize())
                    else PageView(s.pages, s.document.binaries, Modifier.fillMaxSize())
                }
            }
        }
    }
}
