package com.pika

import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import com.pika.core.download.DownloadManager
import com.pika.core.log.AppSessionState
import com.pika.core.log.CrashReporter
import com.pika.core.log.LogStore
import com.pika.core.log.MainThreadWatchdog
import com.pika.core.source.SourceManager
import com.pika.core.update.UpdateManager
import com.pika.core.update.UpdateState
import com.pika.data.AuthorFavourites
import com.pika.data.CategorySettings
import com.pika.data.FollowFeedCache
import com.pika.data.FollowSettings
import com.pika.data.GridSettings
import com.pika.data.JmRemovalMigration
import com.pika.data.RankCache
import com.pika.data.ReaderPrefs
import com.pika.data.ReaderStatus
import com.pika.data.SearchHistory
import com.pika.data.SecureAccountStore
import com.pika.data.SourcePrefs
import com.pika.data.UpdatedAtCache
import com.pika.image.CoalescingInterceptor
import com.pika.image.CoilRetryInterceptor
import com.pika.image.ErrorLoggingInterceptor
import com.pika.network.BcTls
import com.pika.network.PicaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PiKAApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 崩溃/异常退出取证最先安装（ANR、系统杀进程都不走 Java 异常，取证通道见 CrashReporter）；
        // 看门狗记录主线程阻塞栈并落前台心跳
        CrashReporter.install(this)
        MainThreadWatchdog.start(this)
        AppSessionState.register(this)
        // 禁漫源下线一次性迁移：必须最先执行（先于一切缓存预热，见 JmRemovalMigration）
        JmRemovalMigration.run(this)
        SourcePrefs.init(this)
        ReaderPrefs.init(this)
        GridSettings.init(this)
        // 已读/读完状态全量预热可能较慢，放到后台，UI 用状态位等待
        appScope.launch { ReaderStatus.loadAll(this@PiKAApp) }
        CategorySettings.init(this)
        AuthorFavourites.init(this)
        FollowSettings.init(this)
        FollowFeedCache.init(this)
        RankCache.init(this)
        UpdatedAtCache.init(this)
        SearchHistory.init(this)
        SecureAccountStore.init(this)
        UpdateState.init(this)
        DownloadManager.init(this)
        SourceManager.init()
        // 安装 BouncyCastle TLS（绕过 Cloudflare 对 BoringSSL 的指纹拦截）
        BcTls.install()
        // 让 Coil 图片加载也走 BC TLS，否则漫画图片会被 Cloudflare 拦截
        // 内存缓存显式抬到 32%（默认 ~25%）：单张长图解码后上限约 35MB，
        // 默认配额只够驻留 2-3 张——阅读器快滚长章节必然反复解码 + GC 抖动。
        // 抬到 32% 后主流设备可驻留 3-4 张，覆盖「前页回看 + 当前 + 预载 2 页
        // + 同页多切片」的最坏窗口（解码上限体系见 WebtoonSplitPage）。
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(BcTls.imageLoaderClient)
                // 在途请求合流：同 URL 同尺寸的并发请求只下载/解码一次（长图多切片、
                // 网格同封面等场景，见 CoalescingInterceptor 类注释）
                .components {
                    // 错误取证放最外（记录最终结果，含 HTTP 层不可见的体流中断/解码失败）
                    add(ErrorLoggingInterceptor())
                    // 管线级自动重试：覆盖体流中断/解码失败（RetryInterceptor 只见响应头，
                    // 此类失败对 HTTP 层不可见——弱网「手点必好」的根因，见类注释）
                    add(CoilRetryInterceptor())
                    // 在途请求合流：同 URL 同尺寸的并发请求只下载/解码一次（长图多切片、
                    // 网格同封面等场景，见 CoalescingInterceptor 类注释）
                    add(CoalescingInterceptor())
                }
                // 全局图片淡入（A1）：所有 Coil 请求统一 ~150ms crossfade，
                // 网格/详情/下载卡的封面不再生硬闪现；封面底色占位由各卡片的
                // surfaceVariant 色块承担
                .crossfade(true)
                .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.32).build() }
                .build(),
        )
        PicaClient.init(this)
        // S-5.13：日志页此前在无网络活动时永远"暂无日志"，无法判断进程是否刚重启/
        // 排障无从下手。记一条启动基线，后续登录态变化、关键请求失败都会陆续进 LogStore
        LogStore.log("App", "I", "应用启动 v${UpdateManager.currentVersionName}")
    }

    /**
     * 系统内存压力响应：RUNNING_LOW 起清空 Coil 图片内存缓存（单张长图解码后
     * 可达 35MB，是本应用最大内存头），降低长图连续加载时被系统按内存不足
     * 杀进程（lmkd）的风险——此类杀进程无任何 Java 异常，只能靠退出原因取证。
     * 事件写入 LogStore，随崩溃/诊断报告留存为证据。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                val cache = Coil.imageLoader(this).memoryCache
                val beforeMb = (cache?.size ?: 0) / (1024 * 1024)
                cache?.clear()
                LogStore.log(
                    "Memory", "W",
                    "系统内存紧张(level=$level)，已清空图片内存缓存(原约${beforeMb}MB)以降低被杀风险",
                )
            }
        }
    }
}