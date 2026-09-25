package com.pika.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.downloadDataStore by preferencesDataStore(name = "pika_download")

/**
 * 下载偏好：完成通知开关（默认开）、仅 Wi-Fi 下载开关（默认关）。
 *
 * 热点值内存缓存：DownloadManager 的调度协程（IO 线程）每次 pump 都要读 wifiOnly，
 * 必须走内存读取，不能每次读盘。
 */
object DownloadPrefs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cachedNotifyEnabled: Boolean? = null
    @Volatile private var cachedWifiOnly: Boolean? = null

    private lateinit var appContext: Context

    private val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
    private val WIFI_ONLY = booleanPreferencesKey("wifi_only")

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            runCatching {
                val prefs = appContext.downloadDataStore.data.first()
                cachedNotifyEnabled = prefs[NOTIFY_ENABLED]
                cachedWifiOnly = prefs[WIFI_ONLY]
            }
        }
    }

    /** 下载完成系统通知（默认开） */
    val notifyEnabled: Boolean get() = cachedNotifyEnabled ?: true

    val notifyEnabledFlow: Flow<Boolean> = appContext.downloadDataStore.data
        .map { it[NOTIFY_ENABLED] ?: true }
        .distinctUntilChanged()

    suspend fun setNotifyEnabled(enabled: Boolean) {
        cachedNotifyEnabled = enabled
        appContext.downloadDataStore.edit { it[NOTIFY_ENABLED] = enabled }
    }

    /** 仅 Wi-Fi 下载（默认关）：开启后流量网络下任务排队不启动，连上 Wi-Fi 自动续跑 */
    val wifiOnly: Boolean get() = cachedWifiOnly ?: false

    val wifiOnlyFlow: Flow<Boolean> = appContext.downloadDataStore.data
        .map { it[WIFI_ONLY] ?: false }
        .distinctUntilChanged()

    suspend fun setWifiOnly(enabled: Boolean) {
        cachedWifiOnly = enabled
        appContext.downloadDataStore.edit { it[WIFI_ONLY] = enabled }
    }
}
