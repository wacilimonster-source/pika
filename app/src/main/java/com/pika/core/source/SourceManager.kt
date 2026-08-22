package com.pika.core.source

import android.util.Log
import com.pika.data.SourcePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 单一活动源管理器：设置页切换，全局生效。
 */
object SourceManager {
    private val _activeSource =
        MutableStateFlow<SourceType>(SourcePrefs.current().activeSource)

    private val _unauthorizedTick = MutableStateFlow(0)

    val activeSource: StateFlow<SourceType> = _activeSource

    /** 登录态丢失事件计数：每次 401 / 退出登录自增，UI 据此重查登录态并跳登录页 */
    val unauthorizedTick: StateFlow<Int> = _unauthorizedTick

    private val sources: Map<SourceType, Source> = mapOf(
        SourceType.PICACG to PicacgSource(),
        SourceType.JMCOMIC to JmcomicSource(),
    )

    fun init() {
        Log.d("SourceManager", "active source: ${_activeSource.value}")
    }

    /** 切换数据源（suspend，调用方需在协程中） */
    suspend fun switch(type: SourceType) {
        SourcePrefs.current().setActiveSource(type)
        _activeSource.value = type
    }

    fun current(): Source = sources.getValue(_activeSource.value)

    fun sourceOf(type: SourceType): Source = sources.getValue(type)

    fun picaToken(): String? = SourcePrefs.current().picaToken

    /** 401 处理（suspend，由 PicaHttpEngine 在 IO 协程中调用） */
    suspend fun onUnauthorized() {
        sources.getValue(_activeSource.value).logout()
        _unauthorizedTick.value += 1
    }
}
