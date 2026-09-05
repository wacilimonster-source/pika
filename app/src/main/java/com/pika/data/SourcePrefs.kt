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
    const val JM_TOKEN = "jm_token"
    const val JM_BASE = "jm_base"
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
    @Volatile private var cachedJmToken: String? = null
    @Volatile private var cachedJmBaseUrl: String? = null

    private fun loadCache() {
        runCatching {
            runBlocking {
                val prefs = appContext.dataStore.data.first()
                cachedSource = SourceType.entries.firstOrNull { it.name == prefs[Keys.ACTIVE_SOURCE] }
                cachedAppUuid = prefs[Keys.APP_UUID]
                cachedPicaToken = prefs[Keys.PICA_TOKEN]?.takeIf { it.isNotEmpty() }
                cachedPicaEmail = prefs[Keys.PICA_EMAIL]
                cachedJmToken = prefs[stringPreferencesKey(Keys.JM_TOKEN)]?.takeIf { it.isNotEmpty() }
                cachedJmBaseUrl = prefs[stringPreferencesKey(Keys.JM_BASE)]?.takeIf { it.isNotEmpty() }
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

    suspend fun setActiveSource(value: SourceType) {
        cachedSource = value
        appContext.dataStore.edit { it[Keys.ACTIVE_SOURCE] = value.name }
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

    // ---------- 禁漫登录态 ----------

    val jmToken: String?
        get() = cachedJmToken
            ?: runBlocking {
                appContext.dataStore.data.first()[stringPreferencesKey(Keys.JM_TOKEN)]
                    ?.takeIf { it.isNotEmpty() }
            }

    suspend fun setJmLogin(token: String) {
        cachedJmToken = token
        appContext.dataStore.edit {
            it[stringPreferencesKey(Keys.JM_TOKEN)] = token
        }
    }

    suspend fun clearJmLogin() {
        cachedJmToken = null
        appContext.dataStore.edit {
            it.remove(stringPreferencesKey(Keys.JM_TOKEN))
        }
    }

    // ---------- 禁漫 API 域名 ----------

    val jmBaseUrl: String?
        get() = cachedJmBaseUrl
            ?: runBlocking {
                appContext.dataStore.data.first()[stringPreferencesKey(Keys.JM_BASE)]
                    ?.takeIf { it.isNotEmpty() }
            }

    suspend fun setJmBaseUrl(value: String) {
        cachedJmBaseUrl = value
        appContext.dataStore.edit {
            it[stringPreferencesKey(Keys.JM_BASE)] = value
        }
    }
}
