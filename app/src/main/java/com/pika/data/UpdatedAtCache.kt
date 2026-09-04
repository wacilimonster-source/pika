package com.pika.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 作品更新时间记录缓存：任何接口只要拉到 updatedAt 就记下来（详情页是主要来源，
 * 哔咔列表接口不返回该字段）。关注流等列表展示时对没有时间的条目回填已记录的值。
 */
object UpdatedAtCache {
    private const val PREFS_NAME = "updated_at_cache"
    private const val KEY_MAP = "map_json"
    private const val MAX_ENTRIES = 2000

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    /** LinkedHashMap 保持插入序 = 记录新鲜度，超出上限淘汰最旧 */
    private val map = LinkedHashMap<String, String>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(map) {
            runCatching {
                val str = prefs?.getString(KEY_MAP, null) ?: return
                map.clear()
                json.decodeFromString<Map<String, String>>(str).forEach { (k, v) -> map[k] = v }
            }
        }
    }

    fun of(comicId: String): String? = synchronized(map) { map[comicId] }

    /** 拉到更新时间就记录（同值重复记录不触发 version，避免列表无谓刷新） */
    fun put(comicId: String, updatedAt: String) {
        if (updatedAt.isBlank()) return
        synchronized(map) {
            if (map[comicId] == updatedAt) return
            map.remove(comicId)
            map[comicId] = updatedAt
            while (map.size > MAX_ENTRIES) {
                map.remove(map.keys.first())
            }
        }
        _version.value++
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val snapshot = synchronized(map) { json.encodeToString(map.toMap()) }
        p.edit().putString(KEY_MAP, snapshot).apply()
    }
}
