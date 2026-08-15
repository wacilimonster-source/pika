package com.pika

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.pika.data.CategorySettings
import com.pika.data.ReaderPrefs
import com.pika.data.SourcePrefs
import com.pika.core.source.SourceManager
import com.pika.network.BcTls
import com.pika.network.PicaClient

class PiKAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SourcePrefs.init(this)
        ReaderPrefs.init(this)
        com.pika.data.ReaderStatus.loadAll(this)
        CategorySettings.init(this)
        com.pika.data.AuthorFavourites.init(this)
        com.pika.data.FollowSettings.init(this)
        com.pika.data.FollowFeedCache.init(this)
        com.pika.core.download.DownloadManager.init(this)
        SourceManager.init()
        // 安装 BouncyCastle TLS（绕过 Cloudflare 对 BoringSSL 的指纹拦截）
        BcTls.install()
        // 让 Coil 图片加载也走 BC TLS，否则漫画图片会被 Cloudflare 拦截
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(BcTls.imageLoaderClient).build())
        PicaClient.init(this)
    }
}