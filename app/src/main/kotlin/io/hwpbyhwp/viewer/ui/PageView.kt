package io.hwpbyhwp.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.hwpbyhwp.core.layout.Page

/** Page mode: every page drawn at its real proportions, fit to width, pinch to zoom (1x–4x). */
@Composable
fun PageView(pages: List<Page>, binaries: Map<String, ByteArray>, modifier: Modifier = Modifier) {
    val painter = remember(binaries) { PagePainter(binaries) }
    var zoom by remember { mutableFloatStateOf(1f) }
    BoxWithConstraints(
        modifier
            .background(Color(0xFFDADADA))
            .pointerInput(Unit) {
                // Only two-finger gestures zoom; single-finger drags are left to the scroll containers.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                zoom = (zoom * z).coerceIn(1f, 4f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val density = LocalDensity.current
        val gutter = 8.dp
        val baseWidth = maxWidth - gutter * 2
        val horizontal = rememberScrollState()
        LazyColumn(
            Modifier.horizontalScroll(horizontal, enabled = zoom > 1f),
            contentPadding = PaddingValues(gutter),
            verticalArrangement = Arrangement.spacedBy(gutter),
        ) {
            itemsIndexed(pages) { _, page ->
                val widthDp = baseWidth * zoom
                val heightDp = widthDp * (page.height.toFloat() / page.width)
                val scale = with(density) { widthDp.toPx() } / page.width
                Canvas(
                    Modifier
                        .size(widthDp, heightDp)
                        .shadow(2.dp),
                ) {
                    painter.draw(drawContext.canvas.nativeCanvas, page, scale)
                }
            }
        }
    }
}
