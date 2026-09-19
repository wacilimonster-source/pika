package com.pika.core.download

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载清单（manifest.json）反序列化兼容性——禁漫源移除迁移的输入格式契约。
 *
 * [com.pika.core.download.DownloadManager.purgeJmDownloads] 与任务恢复都按
 * `source` 字段判定任务归属：
 * - v1.5.x 旧清单没有 source 字段 → 必须默认解析为 PICACG（哔咔任务不得误清）
 * - 含 JMCOMIC 任务的清单 → 必须可按字段名精确过滤（迁移删除的依据）
 */
class DownloadTaskCompatTest {

    /** 与 DownloadManager 内部一致的 Json 配置 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(DownloadTask.serializer())

    @Test
    fun `旧版清单无 source 字段时默认解析为哔咔`() {
        // v1.5.50 及更早版本写入的清单形态（无 source / createdAt）
        val legacy = """
            [
              {"comicId":"6219b8ede14c3d601db6daf0","comicTitle":"旧漫画",
               "coverUrl":"https://example.com/a.jpg","order":3,"epTitle":"第1話","pageCount":96}
            ]
        """.trimIndent()
        val tasks = json.decodeFromString(serializer, legacy)
        assertEquals(1, tasks.size)
        assertEquals("PICACG", tasks[0].source)
        // 无 source 的旧任务不属于禁漫 → 迁移过滤谓词必须判 false
        assertTrue(tasks.none { it.source == "JMCOMIC" })
    }

    @Test
    fun `含禁漫任务的清单可按 source 精确过滤`() {
        val mixed = """
            [
              {"comicId":"6219b8ede14c3d601db6daf0","comicTitle":"哔咔漫画",
               "coverUrl":"https://example.com/a.jpg","order":1,"epTitle":"第1話","pageCount":10,
               "source":"PICACG","createdAt":1789798595766},
              {"comicId":"439521","comicTitle":"禁漫漫画",
               "coverUrl":"https://example.com/b.jpg","order":2,"epTitle":"chapter 2","pageCount":20,
               "source":"JMCOMIC","createdAt":1789798595767}
            ]
        """.trimIndent()
        val tasks = json.decodeFromString(serializer, mixed)
        assertEquals(2, tasks.size)
        // 迁移过滤：仅清 JMCOMIC，哔咔任务原样保留
        val jm = tasks.filter { it.source == "JMCOMIC" }
        assertEquals(listOf("439521"), jm.map { it.comicId })
        assertEquals(1, tasks.filterNot { it.source == "JMCOMIC" }.size)
    }

    @Test
    fun `过滤后重序列化的清单不产生多余字段且可再反序列化`() {
        val tasks = json.decodeFromString(
            serializer,
            """
            [{"comicId":"439521","comicTitle":"禁漫漫画","coverUrl":"https://example.com/b.jpg",
              "order":1,"epTitle":"c1","pageCount":1,"source":"JMCOMIC","createdAt":1}]
            """.trimIndent(),
        )
        val kept = tasks.filterNot { it.source == "JMCOMIC" }
        val rewritten = json.encodeToString(serializer, kept)
        // 往返一致：迁移重写的清单下次启动恢复不丢任务、不崩
        assertEquals(kept, json.decodeFromString(serializer, rewritten))
    }
}
