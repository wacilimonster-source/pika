package com.pika.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页口径与页码窗口的边界行为。
 *
 * 这些数值同时被分页条、阅读状态筛选切片和多词搜索结果页使用；
 * 任何一处改成「自己算」都会让页数与真实数据错位。
 */
class PageMathTest {

    @Test
    fun `总页数向上取整且空列表保留一页`() {
        assertEquals(1, pageCount(0))
        assertEquals(1, pageCount(1))
        assertEquals(1, pageCount(SERVICE_PAGE_SIZE))
        assertEquals(2, pageCount(SERVICE_PAGE_SIZE + 1))
        assertEquals(5, pageCount(SERVICE_PAGE_SIZE * 5))
    }

    @Test
    fun `已加载页数与总页数同一口径`() {
        assertEquals(1, emptyList<Int>().loadedPageCount())
        assertEquals(1, listOf(1).loadedPageCount())
        assertEquals(2, (1..(SERVICE_PAGE_SIZE + 1)).toList().loadedPageCount())
    }

    @Test
    fun `按页切片不越界且保持顺序`() {
        val items = (1..45).toList()
        assertEquals((1..20).toList(), items.pageSlice(1))
        assertEquals((21..40).toList(), items.pageSlice(2))
        assertEquals((41..45).toList(), items.pageSlice(3))
        assertTrue(items.pageSlice(4).isEmpty())
        assertTrue(items.pageSlice(0).isEmpty())
        assertTrue(items.pageSlice(-1).isEmpty())
        assertTrue(emptyList<Int>().pageSlice(1).isEmpty())
    }

    @Test
    fun `页码窗口包含首尾页并按省略号折叠`() {
        assertEquals(listOf(1), pageWindow(1, 1))
        assertEquals((1..7).toList(), pageWindow(4, 7))
        assertEquals(listOf(1, 2, 3, null, 20), pageWindow(2, 20))
        assertEquals(listOf(1, null, 9, 10, 11, null, 20), pageWindow(10, 20))
        assertEquals(listOf(1, null, 18, 19, 20), pageWindow(19, 20))
    }
}
