package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.readerDataStore by preferencesDataStore(name = "pika_reader")

private object ReaderKeys {
    const val PROGRESS_PREFIX = "progress_"
    const val FINISHED_PREFIX = "finished_"
    const val READER_MODE = "reader_mode"
    const val BRIGHTNESS = "reader_brightness"
    const val RECENT_READS = "recent_reads"
    const val HIDE_BOTTOM_BAR = "hide_bottom_bar_in_reader"
}

/** 最近阅读条目（首页"继续阅读"用） */
@kotlinx.serialization.Serializable
data class RecentRead(
    val comicId: String,
    val title: String,
    val coverUrl: String,
    val author: String = "",
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

    /** 标记作品已读完（读到最后一章最后一页时调用；幂等，重复写无副作用） */
    suspend fun saveFinished(comicId: String) {
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + comicId)] = "1"
        }
    }

    /** 一次性读取全部进度/读完标记（启动预热内存缓存用；finished_ 优先于 progress_，避免被降级覆盖） */
    suspend fun loadAllStatuses(): Map<String, ReadStatus> = runCatching {
        val prefs = appContext.readerDataStore.data.first()
        val result = mutableMapOf<String, ReadStatus>()
        // 先处理 progress_，再处理 finished_：后者无条件覆盖，与迭代顺序无关
        prefs.asMap().forEach { (key, _) ->
            if (key.name.startsWith(ReaderKeys.PROGRESS_PREFIX)) {
                result[key.name.removePrefix(ReaderKeys.PROGRESS_PREFIX)] = ReadStatus.READ
            }
        }
        prefs.asMap().forEach { (key, _) ->
            if (key.name.startsWith(ReaderKeys.FINISHED_PREFIX)) {
                result[key.name.removePrefix(ReaderKeys.FINISHED_PREFIX)] = ReadStatus.FINISHED
            }
        }
        result
    }.getOrDefault(emptyMap())

    // ── 热点值内存缓存：避免 getter/setter 在主线程 runBlocking 读盘 ──────
    @Volatile private var cachedReaderMode: Int? = null
    @Volatile private var cachedBrightness: Float? = null
    @Volatile private var cachedRecentReads: List<RecentRead>? = null

    /** 阅读模式：0=滚动流（条漫），1=横滑翻页 */
    var readerMode: Int
        get() = cachedReaderMode
            ?: runCatching {
                runBlocking {
                    appContext.readerDataStore.data.first()[intPreferencesKey(ReaderKeys.READER_MODE)]
                }
            }.getOrNull() ?: 0
        set(value) {
            cachedReaderMode = value
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
        get() = cachedBrightness
            ?: runCatching {
                runBlocking {
                    appContext.readerDataStore.data.first()[floatPreferencesKey(ReaderKeys.BRIGHTNESS)]
                }
            }.getOrNull() ?: 1.0f
        set(value) {
            val v = value.coerceIn(0.2f, 1.0f)
            cachedBrightness = v
            runCatching {
                runBlocking {
                    appContext.readerDataStore.edit {
                        it[floatPreferencesKey(ReaderKeys.BRIGHTNESS)] = v
                    }
                }
            }
        }

    /**
     * 阅读时是否隐藏底部导航栏（默认开）。
     *
     * 以 Flow 暴露，MainScreen 可直接 collectAsState，改设置后无需重启即时生效。
     */
    val hideBottomBarInReader: Flow<Boolean> = appContext.readerDataStore.data
        .map { it[booleanPreferencesKey(ReaderKeys.HIDE_BOTTOM_BAR)] ?: true }
        .distinctUntilChanged()

    suspend fun setHideBottomBarInReader(enabled: Boolean) {
        appContext.readerDataStore.edit {
            it[booleanPreferencesKey(ReaderKeys.HIDE_BOTTOM_BAR)] = enabled
        }
    }

    // ── 最近阅读（首页"继续阅读"） ─────────────────────────────────────────
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun recentReads(): List<RecentRead> {
        cachedRecentReads?.let { return it }
        val list = runCatching {
            runBlocking {
                val raw = appContext.readerDataStore.data.first()
                    .get(stringPreferencesKey(ReaderKeys.RECENT_READS))
                if (raw.isNullOrBlank()) return@runBlocking emptyList()
                runCatching {
                    json.decodeFromString<List<RecentRead>>(raw)
                }.getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())
        cachedRecentReads = list
        return list
    }

    /** 记录/刷新最近阅读（最近 6 条，按时间倒序） */
    suspend fun recordRecentRead(
        comicId: String,
        title: String,
        coverUrl: String,
        author: String,
        order: Int,
        pageIndex: Int,
    ) {
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<RecentRead>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val entry = RecentRead(comicId, title, coverUrl, author, order, pageIndex, System.currentTimeMillis())
            val updated = (listOf(entry) + current.filterNot { it.comicId == comicId }).take(6)
            prefs[key] = json.encodeToString(updated)
            cachedRecentReads = updated
        }
    }
}
