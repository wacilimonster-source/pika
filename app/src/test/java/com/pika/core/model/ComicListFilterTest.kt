package com.pika.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表页共用的「连载状态筛选 + 本地排序」不变量。
 *
 * 分类页与作者页曾各复制一份 when 分支；这组测试锁定共享实现，
 * 防止后续只改一处导致两个页面行为分叉。
 */
class ComicListFilterTest {

    private fun comic(
        id: String,
        finished: Boolean,
        updatedAt: String,
        likes: Long = 0,
        views: Long = 0,
    ) = ComicSummary(
        id = id,
        title = id,
        author = "a",
        coverUrl = null,
        finished = finished,
        updatedAt = updatedAt,
        totalLikes = likes,
        totalViews = views,
    )

    private val list = listOf(
        comic("finished-old", finished = true, updatedAt = "2026-01-01", likes = 10, views = 100),
        comic("ongoing-new", finished = false, updatedAt = "2026-03-01", likes = 30, views = 300),
        comic("ongoing-mid", finished = false, updatedAt = "2026-02-01", likes = 20, views = 200),
    )

    @Test
    fun `ALL 不丢失任何条目`() {
        assertEquals(list, list.filterByStatus(ComicStatus.ALL))
    }

    @Test
    fun `已完结与连载中互斥`() {
        assertEquals(
            listOf("finished-old"),
            list.filterByStatus(ComicStatus.FINISHED).map { it.id },
        )
        assertEquals(
            listOf("ongoing-new", "ongoing-mid"),
            list.filterByStatus(ComicStatus.ONGOING).map { it.id },
        )
    }

    @Test
    fun `四种排序与服务端语义一致`() {
        assertEquals(
            listOf("ongoing-new", "ongoing-mid", "finished-old"),
            list.applyStatusAndSort(ComicStatus.ALL, ComicSort.DD).map { it.id },
        )
        assertEquals(
            listOf("finished-old", "ongoing-mid", "ongoing-new"),
            list.applyStatusAndSort(ComicStatus.ALL, ComicSort.DA).map { it.id },
        )
        assertEquals(
            listOf("ongoing-new", "ongoing-mid", "finished-old"),
            list.applyStatusAndSort(ComicStatus.ALL, ComicSort.LD).map { it.id },
        )
        assertEquals(
            listOf("ongoing-new", "ongoing-mid", "finished-old"),
            list.applyStatusAndSort(ComicStatus.ALL, ComicSort.VD).map { it.id },
        )
    }

    @Test
    fun `筛选与排序组合时先过滤再重排`() {
        assertEquals(
            listOf("ongoing-mid", "ongoing-new"),
            list.applyStatusAndSort(ComicStatus.ONGOING, ComicSort.DA).map { it.id },
        )
    }
}
