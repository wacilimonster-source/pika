package com.pika.data

import com.pika.core.source.ComicRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 最近阅读条目的作品标识推导——禁漫源移除迁移（purgeJmReaderPrefs）的判定依据。
 *
 * 存量条目的 source 字段形态：
 * - 空（ComicRef 上线前写入）→ 按哔咔解释，ref 为 `PICACG_<裸id>`
 * - "PICACG" → 正常拼装
 * - "JMCOMIC" → 已移除的源：ref 回落哔咔前缀（迁移按 source == "JMCOMIC" 过滤整条，
 *   不会让禁漫条目以哔咔身份出现在"继续阅读"里）
 */
class RecentReadRefTest {

    private fun entry(source: String) = RecentRead(
        comicId = "abc123",
        title = "t",
        coverUrl = "",
        order = 1,
        pageIndex = 0,
        ts = 0L,
        source = source,
    )

    @Test
    fun `历史无源条目按哔咔拼装标识`() {
        assertEquals(ComicRef.ofName("", "abc123"), entry("").ref)
        assertEquals("PICACG_abc123", entry("").ref)
    }

    @Test
    fun `哔咔条目标识正常拼装`() {
        assertEquals("PICACG_abc123", entry("PICACG").ref)
    }

    @Test
    fun `禁漫条目标识回落哔咔前缀且 id 不丢`() {
        // 迁移清除前的存量禁漫条目：回落行为保证不崩溃、不丢 id，
        // 真正的清除由 purgeJmReaderPrefs 按 source 字段过滤完成
        assertEquals("PICACG_abc123", entry("JMCOMIC").ref)
    }
}
