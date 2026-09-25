package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.searchDataStore by preferencesDataStore(name = "pika_search")

/**
 * 搜索历史（最近 10 条，去重置顶，溢出淘汰最旧）。
 * 与数据源无关（换源共用历史）；清空入口在搜索页。
 */
object SearchHistory {

    private const val KEY = "search_history"
    private const val MAX = 10
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 同步读盘（低频入口用；UI 态请走 [historyAsync]） */
    fun history(): List<String> = runCatching {
        runBlocking { historyAsync() }
    }.getOrDefault(emptyList())

    /** 协程上下文中的推荐入口：挂起读盘，不阻塞调用线程 */
    suspend fun historyAsync(): List<String> = runCatching {
        val raw = appContext.searchDataStore.data.first()[stringPreferencesKey(KEY)]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
    }.getOrDefault(emptyList())

    /** 记录关键词：去重置顶（忽略大小写），返回更新后的列表 */
    suspend fun record(keyword: String): List<String> {
        val kw = keyword.trim()
        if (kw.isBlank()) return historyAsync()
        val updated = (listOf(kw) + historyAsync().filterNot { it.equals(kw, ignoreCase = true) }).take(MAX)
        runCatching {
            appContext.searchDataStore.edit { it[stringPreferencesKey(KEY)] = json.encodeToString(updated) }
        }
        return updated
    }

    suspend fun clear() {
        runCatching {
            appContext.searchDataStore.edit { it.remove(stringPreferencesKey(KEY)) }
        }
    }
}
