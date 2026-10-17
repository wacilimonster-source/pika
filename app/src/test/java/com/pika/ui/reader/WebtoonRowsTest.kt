package com.pika.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动流「页 ⇄ 行」纯函数换算测试（页面卡顿 P1 改造的回归保护）。
 *
 * 语义约定（与 ReaderScreen 旧实现的 buildList rows 展开一致）：
 * 每页占 sliceCount(p) 行；virtual rows[i] = (page, slice) 平铺；
 * rowForPage(p) = sum(sliceCount(0..p-1))；sliceOfRow 直接给出 (page, slice)。
 */
class WebtoonRowsTest {

    /** 不切分的普通章节：1 行/页 */
    private val flatPrefix = WebtoonRows.prefixSum(5) { 1 }
    /** 混合：[1, 3, 1, 4, 1] → 总行 10 */
    private val mixedPrefix = WebtoonRows.prefixSum(5) { listOf(1, 3, 1, 4, 1)[it] }
    /** 空章节 */
    private val emptyPrefix = WebtoonRows.prefixSum(0) { 1 }

    @Test
    fun `prefixSum boundaries are cumulative`() {
        assertTrue(flatPrefix.contentEquals(intArrayOf(0, 1, 2, 3, 4, 5)))
        assertTrue(mixedPrefix.contentEquals(intArrayOf(0, 1, 4, 5, 9, 10)))
        assertTrue(emptyPrefix.contentEquals(intArrayOf(0)))
    }

    @Test
    fun `pageOfRow maps flat rows`() {
        repeat(5) { r -> assertEquals(r, WebtoonRows.pageOfRow(flatPrefix, 5, r)) }
    }

    @Test
    fun `pageOfRow maps mixed rows across slice boundaries`() {
        // 页 0 → 行 0；页 1（3 屏）→ 行 1,2,3；页 2 → 行 4；页 3（4 屏）→ 行 5..8；页 4 → 行 9
        assertEquals(0, WebtoonRows.pageOfRow(mixedPrefix, 5, 0))
        assertEquals(1, WebtoonRows.pageOfRow(mixedPrefix, 5, 1))
        assertEquals(1, WebtoonRows.pageOfRow(mixedPrefix, 5, 3))
        assertEquals(2, WebtoonRows.pageOfRow(mixedPrefix, 5, 4))
        assertEquals(3, WebtoonRows.pageOfRow(mixedPrefix, 5, 5))
        assertEquals(3, WebtoonRows.pageOfRow(mixedPrefix, 5, 8))
        assertEquals(4, WebtoonRows.pageOfRow(mixedPrefix, 5, 9))
    }

    @Test
    fun `pageOfRow clamps beyond last row to last page`() {
        // 结束区行号会越界：必须钳在最后一页且不抛异常
        assertEquals(4, WebtoonRows.pageOfRow(mixedPrefix, 5, 10))
        assertEquals(4, WebtoonRows.pageOfRow(mixedPrefix, 5, 99))
        assertEquals(0, WebtoonRows.pageOfRow(emptyPrefix, 0, 0))
    }

    @Test
    fun `sliceOfRow is the flat expansion and round-trips`() {
        // 与旧 rows 列表的语义完全一致：(页, 该页内第几屏)
        var expectedRow = 0
        for (p in 0 until 5) {
            val n = listOf(1, 3, 1, 4, 1)[p]
            for (s in 0 until n) {
                val vertical = WebtoonRows.sliceOfRow(mixedPrefix, 5, expectedRow)
                assertEquals(p, vertical.first)
                assertEquals(s, vertical.second)
                // 该页首行回换必须是本页；切片号为 0
                assertEquals(p, WebtoonRows.pageOfRow(mixedPrefix, 5, mixedPrefix[p]))
                assertEquals(0, WebtoonRows.sliceOfRow(mixedPrefix, 5, mixedPrefix[p]).second)
                expectedRow++
            }
        }
    }

    @Test
    fun `negative row is clamped to first page`() {
        assertEquals(0, WebtoonRows.pageOfRow(mixedPrefix, 5, -3))
        assertEquals(0 to 0, WebtoonRows.sliceOfRow(mixedPrefix, 5, -3))
    }
}
