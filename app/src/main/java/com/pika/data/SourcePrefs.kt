package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pika.core.AppScope
import com.pika.core.source.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "pika_prefs")

private object Keys {
    val ACTIVE_SOURCE = stringPreferencesKey("active_source")
    val PICA_TOKEN = stringPreferencesKey("pica_token")
    val PICA_EMAIL = stringPreferencesKey("pica_email")
    val APP_UUID = stringPreferencesKey("app_uuid")
}

/**
 * DataStore 封装：当前活动源 / 各源登录态 / 设置项 / 设备 UUID
 *
 * 访问范式：
 * - `init()` 在后台协程预热内存缓存；预热完成后所有 getter 都是纯内存读取，零阻塞。
 * - getter 保留 `runBlocking` 兜底分支，仅在"预热未完成 / 登出清空缓存"的极小窗口内触发，
 *   且 DataStore 首次读取后会驻留内存，兜底读取开销为微秒级，不构成热路径阻塞。
 * - 网络层等已在协程上下文的调用方请使用 suspend 版本（如 [getOrCreateAppUuidAsync]）。
 */
class SourcePrefs private constructor(private val appContext: Context) {

    companion object {
        private lateinit var instance: SourcePrefs

        fun init(context: Context) {
            instance = SourcePrefs(context.applicationContext)
            // 冷启动主线程不再同步读盘：热点值由后台协程回填（首次读取涉及文件 IO + 反序列化）
            AppScope.launch { instance.loadCache() }
        }

        fun current(): SourcePrefs = instance
    }

    // ---------- 内存缓存（热点值） ----------
    @Volatile private var cachedSource: SourceType? = null
    @Volatile private var cachedAppUuid: String? = null
    @Volatile private var cachedPicaToken: String? = null
    @Volatile private var cachedPicaEmail: String? = null

    private fun loadCache() {
        runCatching {
            runBlocking {
                val prefs = appContext.dataStore.data.first()
                cachedSource = SourceType.entries.firstOrNull { it.name == prefs[Keys.ACTIVE_SOURCE] }
                cachedAppUuid = prefs[Keys.APP_UUID]
                cachedPicaToken = prefs[Keys.PICA_TOKEN]?.takeIf { it.isNotEmpty() }
                cachedPicaEmail = prefs[Keys.PICA_EMAIL]
            }
        }
    }

    var activeSource: SourceType
        get() = cachedSource
            ?: runBlocking {
                val name = appContext.dataStore.data.first()[Keys.ACTIVE_SOURCE]
                SourceType.entries.firstOrNull { it.name == name } ?: SourceType.PICACG
            }
        set(value) {
            cachedSource = value
            AppScope.launch { appContext.dataStore.edit { it[Keys.ACTIVE_SOURCE] = value.name } }
        }

    // ---------- 设备 UUID（持久化，首次生成） ----------

    /** 协程上下文中的推荐入口：读盘/写盘均挂起，不阻塞调用线程 */
    suspend fun getOrCreateAppUuidAsync(): String {
        cachedAppUuid?.let { return it }

        val existing = appContext.dataStore.data.first()[Keys.APP_UUID]
        if (!existing.isNullOrBlank()) {
            cachedAppUuid = existing
            return existing
        }

        val uuid = java.util.UUID.randomUUID().toString()
        cachedAppUuid = uuid
        appContext.dataStore.edit { it[Keys.APP_UUID] = uuid }
        return uuid
    }

    /** 同步版本：仅供热路径之外、无法改造为协程的调用方（优先用 [getOrCreateAppUuidAsync]） */
    fun getOrCreateAppUuid(): String {
        cachedAppUuid?.let { return it }
        return runBlocking { getOrCreateAppUuidAsync() }
    }

    // ---------- 哔咔登录态 ----------

    val picaToken: String?
        get() = cachedPicaToken
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.PICA_TOKEN]?.takeIf { it.isNotEmpty() }
            }

    val picaEmail: String?
        get() = cachedPicaEmail
            ?: runBlocking { appContext.dataStore.data.first()[Keys.PICA_EMAIL] }

    suspend fun setPicaLogin(token: String, email: String) {
        cachedPicaToken = token
        cachedPicaEmail = email
        appContext.dataStore.edit {
            it[Keys.PICA_TOKEN] = token
            it[Keys.PICA_EMAIL] = email
        }
    }

    suspend fun clearPicaLogin() {
        cachedPicaToken = null
        cachedPicaEmail = null
        appContext.dataStore.edit {
            it.remove(Keys.PICA_TOKEN)
            it.remove(Keys.PICA_EMAIL)
        }
    }
}

/**
 * 禁漫源下线迁移（见 [JmRemovalMigration]）：清除禁漫 token / 域名，并删除活动源标记
 * （旧值可能是已移除的 JMCOMIC；删除后按默认哔咔回落）。
 *
 * 必须在 [SourcePrefs.init] 之前调用，且与本类共用同一 DataStore 实例（同文件重复建实例会抛异常）。
 */
internal suspend fun purgeJmSourcePrefs(context: Context) {
    context.dataStore.edit { prefs ->
        prefs.remove(stringPreferencesKey("jm_token"))
        prefs.remove(stringPreferencesKey("jm_base"))
        prefs.remove(Keys.ACTIVE_SOURCE)
    }
}
