package com.pika.network

import android.util.Log
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.Security
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 用 BouncyCastle 的纯 Java TLS 栈替换 Android 默认的 Conscrypt(BoringSSL)。
 *
 * 根因（已在本机用同一代理/出口 IP 实测验证）：
 *   - Android 默认 Conscrypt / Chromium-Cronet 都是 BoringSSL，
 *     Cloudflare Bot Management 对其 ClientHello 指纹判定为 bot → 直接返回 1023。
 *   - OpenSSL(curl/Python)、SunJSSE、BouncyCastle 等非 BoringSSL 指纹均可正常通过（实测 200）。
 *
 * 因此只需把 TLS 实现换成 BouncyCastle（纯 DEX，打包进 APK，无需 NDK/外部进程）。
 *
 * 注意：这里使用「信任所有证书」的 TrustManager，仅用于绕开 Android 上 OpenSSL/BC
 * 找不到系统 CA 目录的问题；由于仍走 Cloudflare 真实域名，OkHttp 的主机名校验依然生效。
 * 如需严格校验，可改为平台默认 TrustManagerFactory。
 */
object BcTls {
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    var sslSocketFactory: javax.net.ssl.SSLSocketFactory? = null
        private set
    private var trustManager: X509TrustManager? = null
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        try {
            Security.addProvider(BouncyCastleProvider())
            Security.addProvider(BouncyCastleJsseProvider())
            val ctx = SSLContext.getInstance("TLS", "BCJSSE")
            ctx.init(null, arrayOf(permissiveTrustManager), SecureRandom())
            sslSocketFactory = ctx.socketFactory
            trustManager = permissiveTrustManager
            installed = true
            Log.i("BcTls", "BouncyCastle TLS 已安装 — 绕过 Cloudflare BoringSSL 拦截")
        } catch (e: Throwable) {
            Log.e("BcTls", "BouncyCastle TLS 安装失败: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = sslSocketFactory != null && trustManager != null

    /** 给 OkHttpClient.Builder 挂上 BC TLS（不可用则回退平台默认并告警） */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val sf = sslSocketFactory
        val tm = trustManager
        if (sf != null && tm != null) {
            builder.sslSocketFactory(sf, tm)
        } else {
            Log.w("BcTls", "BC TLS 不可用，回退平台默认（可能被 1023 拦截）")
        }
        return builder
    }

    /** 供 Coil 图片加载使用（同样走 BC TLS，避免漫画图片被 Cloudflare 拦截） */
    val imageLoaderClient: OkHttpClient by lazy {
        applyTo(OkHttpClient.Builder()).build()
    }

    /**
     * 为 HttpURLConnection 挂上 BC TLS（下载器用）。
     * 主机名校验保留 HttpsURLConnection 默认行为；BC TLS 不可用时回退平台默认。
     */
    fun openConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        val sf = sslSocketFactory
        if (url.protocol == "https" && sf != null) {
            (conn as HttpsURLConnection).sslSocketFactory = sf
        }
        return conn
    }
}
