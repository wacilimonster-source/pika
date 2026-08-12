package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 */
class SourcePrefs private constructor(private val appContext: Context) {

    companion object {
        private lateinit var instance: SourcePrefs

        fun init(context: Context) {
            instance = SourcePrefs(context.applicationContext)
        }

        fun current(): SourcePrefs = instance
    }

    var activeSource: SourceType
        get() = runBlocking {
            val name = appContext.dataStore.data.first()[Keys.ACTIVE_SOURCE]
            SourceType.entries.firstOrNull { it.name == name } ?: SourceType.PICACG
        }
        set(value) {
            runBlocking { appContext.dataStore.edit { it[Keys.ACTIVE_SOURCE] = value.name } }
        }

    suspend fun setActiveSource(value: SourceType) {
        appContext.dataStore.edit { it[Keys.ACTIVE_SOURCE] = value.name }
    }

    // ---------- 设备 UUID（持久化，首次生成） ----------

    /** 获取或生成设备 UUID（用于 app-uuid 请求头） */
    fun getOrCreateAppUuid(): String {
        val existing = runBlocking {
            appContext.dataStore.data.first()[Keys.APP_UUID]
        }
        if (!existing.isNullOrBlank()) return existing

        val uuid = java.util.UUID.randomUUID().toString()
        runBlocking {
            appContext.dataStore.edit { it[Keys.APP_UUID] = uuid }
        }
        return uuid
    }

    // ---------- 哔咔登录态 ----------

    val picaToken: String?
        get() = runBlocking {
            appContext.dataStore.data.first()[Keys.PICA_TOKEN]?.takeIf { it.isNotEmpty() }
        }

    val picaEmail: String?
        get() = runBlocking { appContext.dataStore.data.first()[Keys.PICA_EMAIL] }

    suspend fun setPicaLogin(token: String, email: String) {
        appContext.dataStore.edit {
            it[Keys.PICA_TOKEN] = token
            it[Keys.PICA_EMAIL] = email
        }
    }

    suspend fun clearPicaLogin() {
        appContext.dataStore.edit {
            it.remove(Keys.PICA_TOKEN)
            it.remove(Keys.PICA_EMAIL)
        }
    }

    // ---------- 禁漫登录态 ----------

    val jmToken: String?
        get() = runBlocking {
            appContext.dataStore.data.first()[stringPreferencesKey(Keys.JM_TOKEN)]
                ?.takeIf { it.isNotEmpty() }
        }

    suspend fun setJmLogin(token: String) {
        appContext.dataStore.edit {
            it[stringPreferencesKey(Keys.JM_TOKEN)] = token
        }
    }

    suspend fun clearJmLogin() {
        appContext.dataStore.edit {
            it.remove(stringPreferencesKey(Keys.JM_TOKEN))
        }
    }

    // ---------- 禁漫 API 域名 ----------

    val jmBaseUrl: String?
        get() = runBlocking {
            appContext.dataStore.data.first()[stringPreferencesKey(Keys.JM_BASE)]
                ?.takeIf { it.isNotEmpty() }
        }

    suspend fun setJmBaseUrl(value: String) {
        appContext.dataStore.edit {
            it[stringPreferencesKey(Keys.JM_BASE)] = value
        }
    }
}
