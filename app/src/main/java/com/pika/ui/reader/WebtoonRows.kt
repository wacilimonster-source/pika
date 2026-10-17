package com.pika.ui.reader

/**
 * 滚动流「页 ⇄ 行」换算（纯函数，JVM 可单测）。
 *
 * 条漫长图按切片数展开成行后，行号与页号/切片号需要双向换算。此前换算依赖一个
 * 物化的 `rows` 列表在组合期全量重建：每解析一张图片上报切片数就触发一次
 * O(总切片数) 分配 + 整屏重组，页数多的章节明显掉帧甚至冻结。
 * 本对象把换算改成「前缀和数组 + 二分」，调用方只持有 IntArray，不再物化行列表。
 */
internal object WebtoonRows {

    /**
     * 前缀和：`result[len]` 为全部页展开后的总行数，`result[p]` 为第 p 页首行行号。
     * 调用方在滚动态筛选下以 `result.last()` 作为 LazyColumn 的 item 数。
     */
    fun prefixSum(pageCount: Int, sliceCountAt: (Int) -> Int): IntArray {
        val arr = IntArray(pageCount + 1)
        for (p in 0 until pageCount) {
            arr[p + 1] = arr[p] + sliceCountAt(p).coerceAtLeast(1)
        }
        return arr
    }

    /** 行号 → 页号。越界（行号 ≥ 总行数，如结束区）钳到最后一页。 */
    fun pageOfRow(prefix: IntArray, pageCount: Int, row: Int): Int {
        if (pageCount <= 0) return 0
        val r = row.coerceAtLeast(0)
        // 二分查找第一个 prefix[p+1] > r 的页
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (prefix[mid + 1] > r) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** 行号 → (页号, 切片号)。行号落在该页切片数之外时切片号越界，由调用方自行钳制。 */
    fun sliceOfRow(prefix: IntArray, pageCount: Int, row: Int): Pair<Int, Int> {
        val page = pageOfRow(prefix, pageCount, row)
        return page to (row.coerceAtLeast(0) - prefix[page])
    }
}
