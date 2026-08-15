package com.pika.ui.browse

import com.pika.core.model.ComicSummary
import com.pika.data.ReadStatus
import com.pika.data.ReaderStatus

/** 阅读状态筛选：ALL=全部，UNREAD=只看未读，UNFINISHED=只看未读完（排除已读完） */
enum class ReadFilter { ALL, UNREAD, UNFINISHED }

/** 筛选选项（chip 标签） */
val readFilterOptions: List<Pair<ReadFilter, String>> = listOf(
    ReadFilter.ALL to "全部",
    ReadFilter.UNREAD to "只看未读",
    ReadFilter.UNFINISHED to "只看未读完",
)

/** 按阅读状态过滤（内存查询，O(n)） */
fun List<ComicSummary>.filterByRead(filter: ReadFilter): List<ComicSummary> = when (filter) {
    ReadFilter.ALL -> this
    ReadFilter.UNREAD -> filter { ReaderStatus.of(it.id) == null }
    ReadFilter.UNFINISHED -> filter { ReaderStatus.of(it.id) != ReadStatus.FINISHED }
}