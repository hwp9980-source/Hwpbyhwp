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
                boundaries(cols, table.cells.sortedWith(compareBy({ it.row }, { it.col })).map { Triple(it.col, it.colSpan, it.width) })
                    ?: solve(cols, table.cells.map { Triple(it.col, it.colSpan, it.width) }),
                boundaries(rows, table.cells.sortedWith(compareBy({ it.col }, { it.row })).map { Triple(it.row, it.rowSpan, it.height) })
                    ?: solve(rows, table.cells.map { Triple(it.row, it.rowSpan, it.height) }),
            )
        }

        /**
         * Track sizes from boundary positions: walking cells in reading order, each cell starts where
         * an earlier cell (in its row, or a spanning one above) ended, so the cell sizes accumulate
         * exactly as Hangul drew them. Returns null when some boundary is never reached.
         */
        private fun boundaries(n: Int, spans: List<Triple<Int, Int, Int>>): IntArray? {
            if (n == 0) return IntArray(0)
            val pos = arrayOfNulls<Int>(n + 1)
            pos[0] = 0
            repeat(2) { // a second pass picks up cells whose start was only known later
                for ((start, count, size) in spans) {
                    if (start > n) continue
                    val x = pos[start] ?: continue
                    val end = (start + count).coerceAtMost(n)
                    if (pos[end] == null) pos[end] = x + size
                }
            }
            if (pos.any { it == null }) return null
            val sizes = IntArray(n) { pos[it + 1]!! - pos[it]!! }
            return if (sizes.all { it >= 0 }) sizes else null
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
