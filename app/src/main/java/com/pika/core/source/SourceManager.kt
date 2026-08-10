package com.pika.core.source

import android.util.Log
import com.pika.data.SourcePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * 单一活动源管理器：设置页切换，全局生效。
 */
object SourceManager {
    private val _activeSource =
        MutableStateFlow<SourceType>(SourcePrefs.current().activeSource)

    private val _loggedOut = MutableStateFlow(false)

    val activeSource: StateFlow<SourceType> = _activeSource

    /** 哔咔 401 触发：清登录态并通知 UI 跳登录页 */
    val loggedOut: StateFlow<Boolean> = _loggedOut

    private val sources: Map<SourceType, Source> = mapOf(
        SourceType.PICACG to PicacgSource(),
        SourceType.JMCOMIC to JmcomicSource(),
    )

    fun init() {
        Log.d("SourceManager", "active source: ${_activeSource.value}")
    }

    fun switch(type: SourceType) {
        runBlocking { SourcePrefs.current().setActiveSource(type) }
        _activeSource.value = type
    }

    fun current(): Source = sources.getValue(_activeSource.value)

    fun sourceOf(type: SourceType): Source = sources.getValue(type)

    fun picaToken(): String? = SourcePrefs.current().picaToken

    fun onUnauthorized() {
        runBlocking { sources.getValue(_activeSource.value).logout() }
        _loggedOut.value = true
    }

    fun consumeLoggedOut(): Boolean {
        val v = _loggedOut.value
        _loggedOut.value = false
        return v
    }
}