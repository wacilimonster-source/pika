package com.pika.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作品标识（`源_id`）解析/生成的关键不变量。
 *
 * 移除禁漫源后（v1.6.0），全 App 只剩哔咔一个源，但存量持久化数据可能仍带
 * `JMCOMIC_` 前缀（升级迁移 [com.pika.data.JmRemovalMigration] 清除前的极端窗口、
 * 或迁移被跳过的异常场景）。本组测试锁定这些输入的回落行为：
 * 一律按哔咔解释，绝不允许抛异常或把 id 丢弃。
 */
class ComicRefTest {

    @Test
    fun `of 与 parse 对哔咔标识互为逆运算`() {
        val ref = ComicRef.of(SourceType.PICACG, "5c1f8b2e3d4a5f6b7c8d9e0f")
        val (source, id) = ComicRef.parse(ref)
        assertEquals(SourceType.PICACG, source)
        assertEquals("5c1f8b2e3d4a5f6b7c8d9e0f", id)
    }

    @Test
    fun `历史裸 id 按哔咔解释`() {
        // ComicRef 上线之前的存量键：不带任何前缀
        val (source, id) = ComicRef.parse("5c1f8b2e3d4a5f6b7c8d9e0f")
        assertEquals(SourceType.PICACG, source)
        assertEquals("5c1f8b2e3d4a5f6b7c8d9e0f", id)
    }

    @Test
    fun `已移除的禁漫前缀回落哔咔且不丢 id`() {
        // 迁移清除前的存量键：不得崩溃、不得丢 id，只按哔咔回落
        val (source, id) = ComicRef.parse("JMCOMIC_439521")
        assertEquals(SourceType.PICACG, source)
        assertEquals("439521", id)
    }

    @Test
    fun `ofName 对未知或缺失源名回落哔咔`() {
        assertEquals(SourceType.PICACG, ComicRef.ofName(null, "abc").let(ComicRef::source))
        assertEquals(SourceType.PICACG, ComicRef.ofName("", "abc").let(ComicRef::source))
        assertEquals(SourceType.PICACG, ComicRef.ofName("JMCOMIC", "abc").let(ComicRef::source))
        // 哔咔源名正常拼装
        assertEquals(
            ComicRef.of(SourceType.PICACG, "abc"),
            ComicRef.ofName("PICACG", "abc"),
        )
    }

    @Test
    fun `id 与 source 取值器正确拆分`() {
        val ref = ComicRef.of(SourceType.PICACG, "abc123")
        assertEquals("abc123", ComicRef.id(ref))
        assertEquals(SourceType.PICACG, ComicRef.source(ref))
    }

    @Test
    fun `id 自身含下划线时按首个分隔符拆分且尾部完整保留`() {
        // 分隔符约定：哔咔 id（24 位十六进制）不含 `_`；parse 以第一个 `_` 分隔，
        // 余串原样保留——即使未来 id 出现下划线也不会丢段
        val id = "abc_def_123"
        val ref = ComicRef.of(SourceType.PICACG, id)
        assertEquals(id, ComicRef.parse(ref).second)
    }
}
