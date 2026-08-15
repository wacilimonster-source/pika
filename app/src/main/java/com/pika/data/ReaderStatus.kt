package com.pika.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** 阅读状态：READ=打开过阅读器（已读），FINISHED=读到最后一章最后一页（已读完，优先级高于已读） */
enum class ReadStatus { READ, FINISHED }

/**
 * 已读/已读完状态的内存缓存：
 * 启动时一次性从 DataStore 全量加载（存量进度自动补齐"已读"），阅读器写入时同步更新。
 * 列表页每条 O(1) 内存查询，不碰磁盘；version 递增触发 Compose 列表刷新角标。
 */
object ReaderStatus {

    private val map = mutableMapOf<String, ReadStatus>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** App 启动时调用：一次性加载全部进度/读完标记（含历史数据） */
    fun loadAll(context: Context) {
        runCatching {
            runBlocking {
                map.clear()
                map.putAll(ReaderPrefs.current().loadAllStatuses())
                _version.value++
            }
        }
    }

    fun of(comicId: String): ReadStatus? = map[comicId]

    /** 打开阅读器即已读；状态只升不降（读完的作品不会被降级为仅已读） */
    fun markRead(comicId: String) {
        if (map.containsKey(comicId)) return
        map[comicId] = ReadStatus.READ
        _version.value++
    }

    fun markFinished(comicId: String) {
        if (map[comicId] == ReadStatus.FINISHED) return
        map[comicId] = ReadStatus.FINISHED
        _version.value++
    }
}
