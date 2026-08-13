package com.pika.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 收藏的作者条目：作者名 + 最近一部作品的封面 */
@Serializable
data class AuthorEntry(
    val author: String,
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 作者收藏持久化（本地）：SharedPreferences 存 JSON。
 * 哔咔服务端无关注作者接口，纯本地功能。
 */
object AuthorFavourites {
    private const val PREFS_NAME = "author_favourites"
    private const val KEY_LIST = "author_list"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 全部收藏作者（按收藏时间倒序） */
    fun get(): List<AuthorEntry> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AuthorEntry>>(str) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    fun contains(author: String): Boolean = get().any { it.author == author }

    /** 收藏（已存在则刷新封面与时间并置顶） */
    fun add(author: String, coverUrl: String = "") {
        if (author.isBlank()) return
        val list = get().filterNot { it.author == author }.toMutableList()
        list.add(0, AuthorEntry(author = author, coverUrl = coverUrl))
        save(list)
    }

    fun remove(author: String) {
        save(get().filterNot { it.author == author })
    }

    private fun save(list: List<AuthorEntry>) {
        val p = prefs ?: return
        p.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
    }
}
