package com.pika.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S-5.11：资料页生日此前显示原始 ISO 串 `1987-04-25T00:00:00.000Z`。
 * datePart 提取 ISO 前缀日期；非 ISO 文本原样返回。
 */
class DateTextTest {

    @Test
    fun `完整 ISO 时间戳提取日期部分`() {
        assertEquals("1987-04-25", DateText.datePart("1987-04-25T00:00:00.000Z"))
    }

    @Test
    fun `已是纯日期时原样返回`() {
        assertEquals("1987-04-25", DateText.datePart("1987-04-25"))
    }

    @Test
    fun `非 ISO 文本与空串不猜测格式`() {
        assertEquals("1990/01/02", DateText.datePart("1990/01/02"))
        assertEquals("未知", DateText.datePart("未知"))
        assertEquals("", DateText.datePart(""))
    }
}
