package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableObject

/**
 * Column and row geometry of a table, derived from the stored cell sizes.
 * Cells only record their own (spanned) width/height, so single-span cells pin each
 * column, and spanning cells fill in columns that no single-span cell covers.
 */
class TableGrid private constructor(
    val colWidths: IntArray,
    val rowHeights: IntArray,
) {
    val width: Int get() = colWidths.sum()
    val height: Int get() = rowHeights.sum()

    fun colX(col: Int): Int = (0 until col.coerceAtMost(colWidths.size)).sumOf { colWidths[it] }
    fun rowY(row: Int): Int = (0 until row.coerceAtMost(rowHeights.size)).sumOf { rowHeights[it] }

    fun cellWidth(cell: TableCell): Int =
        (cell.col until (cell.col + cell.colSpan).coerceAtMost(colWidths.size)).sumOf { colWidths[it] }

    fun cellHeight(cell: TableCell): Int =
        (cell.row until (cell.row + cell.rowSpan).coerceAtMost(rowHeights.size)).sumOf { rowHeights[it] }

    /** Returns a grid whose rows are at least as tall as [contentHeights] (per cell, same order as table.cells) require. */
    fun withContentHeights(cells: List<TableCell>, contentHeights: IntArray): TableGrid {
        val rows = rowHeights.copyOf()
        cells.forEachIndexed { i, c -> if (c.rowSpan == 1 && c.row < rows.size) rows[c.row] = maxOf(rows[c.row], contentHeights[i]) }
        cells.forEachIndexed { i, c ->
            if (c.rowSpan > 1 && c.row < rows.size) {
                val last = (c.row + c.rowSpan - 1).coerceAtMost(rows.size - 1)
                val have = (c.row..last).sumOf { rows[it] }
                if (contentHeights[i] > have) rows[last] += contentHeights[i] - have
            }
        }
        return TableGrid(colWidths.copyOf(), rows)
    }

    companion object {
        fun of(table: TableObject): TableGrid {
            val cols = maxOf(table.colCount, table.cells.maxOfOrNull { it.col + it.colSpan } ?: 0)
            val rows = maxOf(table.rowCount, table.cells.maxOfOrNull { it.row + it.rowSpan } ?: 0)
            return TableGrid(
                solve(cols, table.cells.map { Triple(it.col, it.colSpan, it.width) }),
                solve(rows, table.cells.map { Triple(it.row, it.rowSpan, it.height) }),
            )
        }

        /** Splits spans (start, count, size) into per-track sizes. */
        private fun solve(n: Int, spans: List<Triple<Int, Int, Int>>): IntArray {
            val sizes = IntArray(n) { -1 }
            spans.filter { it.second == 1 && it.first < n }.forEach { (start, _, size) ->
                sizes[start] = maxOf(sizes[start], size)
            }
            // Wider spans first fill unknown tracks with whatever their known neighbours leave over.
            spans.filter { it.second > 1 }.sortedBy { it.second }.forEach { (start, count, size) ->
                val range = start until (start + count).coerceAtMost(n)
                val unknown = range.filter { sizes[it] < 0 }
                if (unknown.isEmpty()) return@forEach
                val known = range.filter { sizes[it] >= 0 }.sumOf { sizes[it] }
                val share = maxOf(0, size - known) / unknown.size
                unknown.forEach { sizes[it] = share }
            }
            val fallback = sizes.filter { it > 0 }.average().takeIf { !it.isNaN() }?.toInt() ?: 1000
            return IntArray(n) { if (sizes[it] < 0) fallback else sizes[it] }
        }
    }
}
