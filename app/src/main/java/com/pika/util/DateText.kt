package com.pika.util

/**
 * 服务端日期文本的展示规整（S-5.11）：资料页生日此前直接显示
 * `1987-04-25T00:00:00.000Z` 原始 ISO 串。提取 ISO 前缀的日期部分展示；
 * 非 ISO 格式原样返回，不猜测格式。
 */
object DateText {

    private val ISO_DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")

    /** `1987-04-25T00:00:00.000Z` → `1987-04-25`；无 ISO 前缀时原样返回 */
    fun datePart(iso: String): String =
        ISO_DATE_PREFIX.find(iso)?.groupValues?.get(1) ?: iso
}
