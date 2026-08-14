package com.pika.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一个关键词关注项：一组关键词，组内为"且"关系 */
@Serializable
data class FollowItem(
    val keywords: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 首页"个人关注"设置：关键词关注（组合关键词，且关系）。
 * 本地 SharedPreferences 存 JSON（哔咔服务端无关注接口，纯本地）。
 */
object FollowSettings {
    private const val PREFS_NAME = "follow_settings"
    private const val KEY_ITEMS = "follow_items"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 关注项（按添加时间倒序） */
    fun items(): List<FollowItem> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FollowItem>>(str) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    fun contains(keywords: List<String>): Boolean = items().any { it.keywords == keywords }

    fun addItem(keywords: List<String>) {
        val words = keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.isEmpty()) return
        val list = items().filterNot { it.keywords == words }.toMutableList()
        var ts = System.currentTimeMillis()
        while (list.any { it.createdAt == ts }) ts++
        list.add(0, FollowItem(keywords = words, createdAt = ts))
        val p = prefs ?: return
        p.edit().putString(KEY_ITEMS, json.encodeToString(list)).apply()
    }

    fun removeItem(createdAt: Long) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_ITEMS, json.encodeToString(items().filterNot { it.createdAt == createdAt }))
            .apply()
    }
}