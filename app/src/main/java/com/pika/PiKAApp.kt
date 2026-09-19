package com.pika

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.pika.data.CategorySettings
import com.pika.data.GridSettings
import com.pika.data.ReaderPrefs
import com.pika.data.SourcePrefs
import com.pika.core.source.SourceManager
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
        // 禁漫源下线一次性迁移：必须最先执行（先于一切缓存预热，见 JmRemovalMigration）
        com.pika.data.JmRemovalMigration.run(this)
        SourcePrefs.init(this)
        ReaderPrefs.init(this)
        GridSettings.init(this)
        // 已读/读完状态全量预热可能较慢，放到后台，UI 用状态位等待
        appScope.launch { com.pika.data.ReaderStatus.loadAll(this@PiKAApp) }
        CategorySettings.init(this)
        com.pika.data.AuthorFavourites.init(this)
        com.pika.data.FollowSettings.init(this)
        com.pika.data.FollowFeedCache.init(this)
        com.pika.data.UpdatedAtCache.init(this)
        com.pika.data.SecureAccountStore.init(this)
        com.pika.core.download.DownloadManager.init(this)
        SourceManager.init()
        // 安装 BouncyCastle TLS（绕过 Cloudflare 对 BoringSSL 的指纹拦截）
        BcTls.install()
        // 让 Coil 图片加载也走 BC TLS，否则漫画图片会被 Cloudflare 拦截
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(BcTls.imageLoaderClient).build())
        PicaClient.init(this)
    }
}