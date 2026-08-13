package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.readerDataStore by preferencesDataStore(name = "pika_reader")

private object ReaderKeys {
    const val PROGRESS_PREFIX = "progress_"
    const val READER_MODE = "reader_mode"
    const val BRIGHTNESS = "reader_brightness"
    const val RECENT_READS = "recent_reads"
}

/** 最近阅读条目（首页"继续阅读"用） */
@kotlinx.serialization.Serializable
data class RecentRead(
    val comicId: String,
    val title: String,
    val coverUrl: String,
    val order: Int,
    val pageIndex: Int,
    val ts: Long,
)

/**
 * 阅读器偏好：本地阅读进度 / 阅读模式 / 亮度。
 *
 * 云端进度同步不可行（当前数据源 API 无进度接口），本地进度保证换章、重进可续读。
 */
class ReaderPrefs private constructor(private val appContext: Context) {

    companion object {
        private lateinit var instance: ReaderPrefs

        fun init(context: Context) {
            instance = ReaderPrefs(context.applicationContext)
        }

        fun current(): ReaderPrefs = instance
    }

    /** 阅读进度：comicId -> (order, pageIndex)；pageIndex 从 0 开始 */
    data class Progress(val order: Int, val pageIndex: Int)

    fun lastProgress(comicId: String): Progress? {
        val raw = runCatching {
            runBlocking {
                appContext.readerDataStore.data.first()[stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + comicId)]
            }
        }.getOrNull() ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val order = parts[0].toIntOrNull() ?: return null
        val page = parts[1].toIntOrNull() ?: return null
        return Progress(order, page)
    }

    suspend fun saveProgress(comicId: String, order: Int, pageIndex: Int) {
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + comicId)] = "$order:$pageIndex"
        }
    }

    /** 阅读模式：0=滚动流（条漫），1=横滑翻页 */
    var readerMode: Int
        get() = runCatching {
            runBlocking {
                appContext.readerDataStore.data.first()[intPreferencesKey(ReaderKeys.READER_MODE)]
            }
        }.getOrNull() ?: 0
        set(value) {
            runCatching {
                runBlocking {
                    appContext.readerDataStore.edit {
                        it[intPreferencesKey(ReaderKeys.READER_MODE)] = value
                    }
                }
            }
        }

    /** 阅读亮度（1.0 为原亮度，越小越暗） */
    var brightness: Float
        get() = runCatching {
            runBlocking {
                appContext.readerDataStore.data.first()[floatPreferencesKey(ReaderKeys.BRIGHTNESS)]
            }
        }.getOrNull() ?: 1.0f
        set(value) {
            runCatching {
                runBlocking {
                    appContext.readerDataStore.edit {
                        it[floatPreferencesKey(ReaderKeys.BRIGHTNESS)] = value.coerceIn(0.2f, 1.0f)
                    }
                }
            }
        }

    // ── 最近阅读（首页"继续阅读"） ─────────────────────────────────────────
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun recentReads(): List<RecentRead> = runCatching {
        runBlocking {
            val raw = appContext.readerDataStore.data.first()
                .get(stringPreferencesKey(ReaderKeys.RECENT_READS))
            if (raw.isNullOrBlank()) return@runBlocking emptyList()
            runCatching {
                json.decodeFromString<List<RecentRead>>(raw)
            }.getOrDefault(emptyList())
        }
    }.getOrDefault(emptyList())

    /** 记录/刷新最近阅读（最近 6 条，按时间倒序） */
    suspend fun recordRecentRead(
        comicId: String,
        title: String,
        coverUrl: String,
        order: Int,
        pageIndex: Int,
    ) {
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<RecentRead>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val entry = RecentRead(comicId, title, coverUrl, order, pageIndex, System.currentTimeMillis())
            val updated = (listOf(entry) + current.filterNot { it.comicId == comicId }).take(6)
            prefs[key] = json.encodeToString(updated)
        }
    }
}
