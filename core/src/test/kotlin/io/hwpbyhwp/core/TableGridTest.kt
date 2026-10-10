package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.TableGrid
import io.hwpbyhwp.core.model.ObjectPlacement
import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.VertAlign
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TableGridTest {

    private fun cell(row: Int, col: Int, w: Int, h: Int, rs: Int = 1, cs: Int = 1) =
        TableCell(row, col, rs, cs, w, h, 0, 0, 0, 0, 0, VertAlign.TOP, emptyList())

    private fun table(rows: Int, cols: Int, vararg cells: TableCell) =
        TableObject(rows, cols, 0, 0, 0, 0, ObjectPlacement.INLINE, cells.toList())

    @Test
    fun simpleGrid() {
        val g = TableGrid.of(table(2, 2, cell(0, 0, 100, 10), cell(0, 1, 200, 10), cell(1, 0, 100, 20), cell(1, 1, 200, 20)))
        assertContentEquals(intArrayOf(100, 200), g.colWidths)
        assertContentEquals(intArrayOf(10, 20), g.rowHeights)
        assertEquals(100, g.colX(1))
    }

    @Test
    fun columnOnlyCoveredBySpan() {
        // Row 0 spans both columns; row 1 pins column 0 only.
        val g = TableGrid.of(table(2, 2, cell(0, 0, 300, 10, cs = 2), cell(1, 0, 120, 10), cell(1, 1, 180, 10)))
        assertContentEquals(intArrayOf(120, 180), g.colWidths)
        val g2 = TableGrid.of(table(2, 2, cell(0, 0, 300, 10, cs = 2), cell(1, 0, 120, 10, cs = 1)))
        assertContentEquals(intArrayOf(120, 180), g2.colWidths)
    }

    @Test
    fun contentGrowsRows() {
        val cells = listOf(cell(0, 0, 100, 10), cell(0, 1, 100, 10, rs = 2), cell(1, 0, 100, 10))
        val g = TableGrid.of(table(2, 2, *cells.toTypedArray())).withContentHeights(cells, intArrayOf(15, 50, 5))
        assertContentEquals(intArrayOf(15, 35), g.rowHeights)
    }

    @Test
    fun columnsFollowCellBoundariesWhenSpansOverlap() {
        // Row 0: 100 | 300 (2 cols). Row 1: 250 (2 cols) | 150. Boundaries 0,100,250,400.
        val g = TableGrid.of(table(2, 3, cell(0, 0, 100, 10), cell(0, 1, 300, 10, cs = 2), cell(1, 0, 250, 10, cs = 2), cell(1, 2, 150, 10)))
        assertContentEquals(intArrayOf(100, 150, 150), g.colWidths)
        assertEquals(400, g.width)
    }
}
