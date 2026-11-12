package com.pika.ui.browse

/**
 * 列表分页的统一口径。
 *
 * 哔咔列表接口每页固定 20 条；客户端切片（阅读状态筛选、多词交集）必须与它保持一致，
 * 否则「已加载页数」与真实页码会错位。此前 20/19 散落在 ViewModel 和两个 Screen 中，
 * 改动时漏一处就会出现分页条显示的页数与实际数据不符。
 */
const val SERVICE_PAGE_SIZE = 20

/** 总页数（向上取整；空列表也至少 1 页，与分页条语义一致） */
fun pageCount(totalItems: Int, pageSize: Int = SERVICE_PAGE_SIZE): Int =
    ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1)

/** 已加载条目数对应的「已加载页数」（向上取整；空列表为 1） */
fun loadedPageCount(loadedItems: Int, pageSize: Int = SERVICE_PAGE_SIZE): Int =
    pageCount(loadedItems, pageSize)

/** 列表版本的已加载页数：`comics.loadedPageCount()` 比分页条调用处直接算 19/20 更不易写错 */
fun Collection<*>.loadedPageCount(pageSize: Int = SERVICE_PAGE_SIZE): Int =
    loadedPageCount(size, pageSize)

/** 按页切片（1-based；越界返回空列表，不抛异常） */
fun <T> List<T>.pageSlice(page: Int, pageSize: Int = SERVICE_PAGE_SIZE): List<T> {
    if (page < 1) return emptyList()
    val from = (page - 1) * pageSize
    if (from >= size) return emptyList()
    return subList(from, minOf(from + pageSize, size))
}
