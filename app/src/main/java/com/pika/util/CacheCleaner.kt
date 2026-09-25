package com.pika.util

import android.content.Context
import coil.Coil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缓存清理：图片磁盘缓存（Coil）+ 内存缓存。
 *
 * 不含已下载章节（downloads/，删了离线就没法看）与条漫切片数缓存
 * （SharedPreferences，仅几 KB 且可自动重建）。
 */
object CacheCleaner {

    /** 图片磁盘缓存体积（字节） */
    suspend fun imageCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        runCatching { Coil.imageLoader(context).diskCache?.size }.getOrNull() ?: 0L
    }

    /** 清理图片磁盘缓存 + 内存缓存，返回清理的字节数 */
    suspend fun clearImageCache(context: Context): Long = withContext(Dispatchers.IO) {
        val loader = Coil.imageLoader(context)
        val size = runCatching { loader.diskCache?.size }.getOrNull() ?: 0L
        runCatching { loader.diskCache?.clear() }
        runCatching { loader.memoryCache?.clear() }
        size
    }
}
