package com.pika.data

import android.content.Context
import android.content.SharedPreferences
import com.pika.core.model.ComicSummary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 排行榜持久化缓存：每个榜型（H24/D7/D30）一份上次成功拉取的结果 + 时间戳。
 *
 * 用途：哔咔服务端故障期间（如 2026-10 Redis MISCONF 全量 500，所有接口在鉴权前
 * 即被拒绝），排行榜加载失败时降级展示上次成功的缓存榜单（错误横幅注明新鲜度），
 * 而不是整页报错不可用。
 */
object RankCache {
    private const val PREFS_NAME = "rank_cache"
    private const val MAX_ENTRIES = 120

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** @return 缓存时间戳 + 榜单；无缓存或缓存损坏时为 null */
    fun load(type: String): Pair<Long, List<ComicSummary>>? {
        val p = prefs ?: return null
        val list = p.getString("list_$type", null)
            ?.let { runCatching { json.decodeFromString<List<ComicSummary>>(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        if (list.isEmpty()) return null
        return p.getLong("at_$type", 0L) to list
    }

    fun save(type: String, list: List<ComicSummary>) {
        val p = prefs ?: return
        p.edit()
            .putString("list_$type", json.encodeToString(list.take(MAX_ENTRIES)))
            .putLong("at_$type", System.currentTimeMillis())
            .apply()
    }
}
