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
import io.hwpbyhwp.viewer.ui.PageView
import io.hwpbyhwp.viewer.ui.ReadingView

class MainActivity : ComponentActivity() {

    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.documentUri()?.let(viewModel::open)
        setContent {
            MaterialTheme {
                ViewerScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.documentUri()?.let(viewModel::open)
    }

    private fun Intent.documentUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(viewModel: ViewerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Many senders label HWP files as octet-stream, so accept anything and let the loader sniff the format.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val openFile = { picker.launch(arrayOf("*/*")) }
    var readingMode by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (val s = state) {
                        is ViewerState.Loaded -> s.name
                        is ViewerState.Loading -> s.name
                        is ViewerState.Failed -> s.name
                        ViewerState.Empty -> "한글 뷰어"
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (state is ViewerState.Loaded) {
                        TextButton(onClick = { readingMode = !readingMode }) { Text(if (readingMode) "쪽 보기" else "읽기 모드") }
                    }
                    TextButton(onClick = openFile) { Text("열기") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                ViewerState.Empty -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("HWP·HWPX 문서를 열어 보세요")
                    Button(onClick = openFile) { Text("파일 열기") }
                }
                is ViewerState.Loading -> CircularProgressIndicator()
                is ViewerState.Failed -> Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(s.message)
                    Button(onClick = openFile) { Text("다른 파일 열기") }
                }
                is ViewerState.Loaded ->
                    if (readingMode) ReadingView(s.document, Modifier.fillMaxSize())
                    else PageView(s.pages, s.document.binaries, Modifier.fillMaxSize())
            }
        }
    }
}
