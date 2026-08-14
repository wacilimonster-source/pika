package com.pika.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 关注的分类标签条目 */
@Serializable
data class FollowCategory(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 首页"个人关注"设置：关键词关注 + 分类标签关注。
 * 本地 SharedPreferences 存 JSON（哔咔服务端无关注接口，纯本地）。
 */
object FollowSettings {
    private const val PREFS_NAME = "follow_settings"
    private const val KEY_KEYWORDS = "follow_keywords"
    private const val KEY_CATEGORIES = "follow_categories"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 关注的关键词（按添加时间倒序） */
    fun keywords(): List<String> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_KEYWORDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(str) }
            .getOrDefault(emptyList())
    }

    fun containsKeyword(keyword: String): Boolean = keywords().any { it == keyword }

    fun addKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val list = keywords().filterNot { it == keyword }.toMutableList()
        list.add(0, keyword)
        val p = prefs ?: return
        p.edit().putString(KEY_KEYWORDS, json.encodeToString(list)).apply()
    }

    fun removeKeyword(keyword: String) {
        val p = prefs ?: return
        p.edit().putString(KEY_KEYWORDS, json.encodeToString(keywords().filterNot { it == keyword })).apply()
    }

    /** 关注的分类标签（按添加时间倒序） */
    fun categories(): List<FollowCategory> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_CATEGORIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FollowCategory>>(str) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    fun containsCategory(id: String): Boolean = categories().any { it.id == id }

    fun addCategory(id: String, title: String) {
        if (id.isBlank()) return
        val list = categories().filterNot { it.id == id }.toMutableList()
        list.add(0, FollowCategory(id = id, title = title))
        val p = prefs ?: return
        p.edit().putString(KEY_CATEGORIES, json.encodeToString(list)).apply()
    }

    fun removeCategory(id: String) {
        val p = prefs ?: return
        p.edit().putString(KEY_CATEGORIES, json.encodeToString(categories().filterNot { it.id == id })).apply()
    }
}