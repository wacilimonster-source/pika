package com.pika.data

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking

/**
 * 禁漫源下线一次性迁移：升级后首次启动时清除全部禁漫相关的存量数据
 * （账号凭据 / token / 域名 / 阅读进度 / 最近阅读 / 已读角标 / 更新时间缓存 /
 * 长图切片缓存 / 下载任务与已下载文件）。
 *
 * 「JMCOMIC」是历史源名——该枚举值已从 SourceType 移除，只存在于旧版本写入的
 * 持久化数据里，故此处按字面量匹配，勿改为枚举引用。
 *
 * 必须在任何源 / 缓存初始化之前（PiKAApp.onCreate 最前）阻塞执行：
 * - ComicRef.parse 与下载任务恢复对未知源名回落哔咔，迁移不先行的话，
 *   残留的 JMCOMIC 键会被当哔咔 id 去请求哔咔接口（错源请求）。
 * - 各存储封装均有启动预热，先清后热可避免脏值进内存缓存。
 */
object JmRemovalMigration {

    private const val TAG = "JmRemovalMigration"
    private const val FLAG_PREFS = "pika_migrations"
    private const val FLAG_KEY = "jm_removal_done"

    /** 历史禁漫源名（SourceType 已移除该值，仅存量数据中出现） */
    internal const val LEGACY_JM = "JMCOMIC"

    fun run(context: Context) {
        val app = context.applicationContext
        val flag = app.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        if (flag.getBoolean(FLAG_KEY, false)) return

        runCatching { runBlocking { purgeJmSourcePrefs(app) } }
            .onFailure { Log.e(TAG, "source prefs purge failed: ${it.message}") }
        runCatching { runBlocking { purgeJmReaderPrefs(app) } }
            .onFailure { Log.e(TAG, "reader prefs purge failed: ${it.message}") }
        runCatching { purgeSharedPreferences(app) }
            .onFailure { Log.e(TAG, "shared prefs purge failed: ${it.message}") }
        runCatching { purgeKeystore() }
            .onFailure { Log.e(TAG, "keystore purge failed: ${it.message}") }
        runCatching { com.pika.core.download.DownloadManager.purgeJmDownloads(app) }
            .onFailure { Log.e(TAG, "downloads purge failed: ${it.message}") }

        flag.edit().putBoolean(FLAG_KEY, true).apply()
        Log.i(TAG, "jm removal migration done")
    }

    /**
     * 清除所有 SharedPreferences 中键名含禁漫源名的条目
     * （SecureAccountStore 的 email_/pass_ 键、UpdatedAtCache、WebtoonSliceCache 等）。
     *
     * 逐文件打开而非点名清理：不遗漏任何存储位，对不含该键的文件是无害空操作。
     */
    private fun purgeSharedPreferences(app: Context) {
        val dir = File(app.applicationInfo.dataDir, "shared_prefs")
        val xmls = dir.listFiles { f -> f.name.endsWith(".xml") } ?: return
        xmls.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            runCatching {
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val doomed = sp.all.keys.filter { it.contains(LEGACY_JM) }
                if (doomed.isNotEmpty()) {
                    sp.edit().apply { doomed.forEach { remove(it) } }.apply()
                    Log.i(TAG, "purged ${doomed.size} key(s) from $name")
                }
            }.onFailure { Log.e(TAG, "purge $name failed: ${it.message}") }
        }
        // 关注流是整体 JSON 缓存（键名不含源名，禁漫条目只出现在值内）：
        // 直接整体失效，冷启动空一轮后随下次刷新重建，成本远低于解析过滤
        runCatching {
            app.getSharedPreferences("follow_feed_cache", Context.MODE_PRIVATE)
                .edit().remove("feed_json").apply()
        }
    }

    /** 删除禁漫账号的 AndroidKeyStore 密钥（别名与 SecureAccountStore.keyAlias 同构） */
    private fun purgeKeystore() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("pika_cred_$LEGACY_JM")
    }
}
