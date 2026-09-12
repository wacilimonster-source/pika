package com.pika.core.pica

/**
 * 哔咔网络层配置：移植自 haka_comic lib/network/utils.dart
 *
 * 关于密钥加固的**如实说明**：
 * 客户端签名密钥在逆向面前终究可被提取，此处目标只是「提高静态提取门槛」。
 * 原实现用 `const val` 分段拼接 —— 但 Kotlin 的 const 是**编译期常量**，
 * 字符串常量相加会在编译期直接求值，产物中不保留任何片段；release 的 R8
 * 常量传播还会进一步合并。也就是说注释声称的防护等于零，反而让人误判风险等级
 * （「以为加固了、其实没有」比不做混淆更危险）。
 *
 * 现改为「运行时按位还原」：字节值以 IntArray 存放（非 String 常量），
 * 完整密钥只在运行时拼出，不参与编译期折叠。
 * 如需更强防护请引入字符串加密（StringFog / DexGuard）或把签名计算移入 NDK。
 */
object PicaConfig {
    private const val KEY_MASK = 0x6F

    // "C69BAF41DA5ABD1FFEDC6D2FEA56B" 逐字节异或 KEY_MASK
    private val API_KEY_XOR = intArrayOf(
        0x2C, 0x59, 0x56, 0x2D, 0x2E, 0x29, 0x5B, 0x5E, 0x2B, 0x2E, 0x5A, 0x2E, 0x2D, 0x2B, 0x5E, 0x29,
        0x29, 0x2A, 0x2B, 0x2C, 0x59, 0x2B, 0x5D, 0x29, 0x2A, 0x2E, 0x5A, 0x59, 0x2D,
    )

    private val SECRET_KEY_XOR = intArrayOf(
        0x11, 0x0B, 0x12, 0x4B, 0x3E, 0x58, 0x4B, 0x0A, 0x26, 0x01, 0x06, 0x52, 0x39, 0x46, 0x56, 0x33,
        0x3D, 0x24, 0x40, 0x3F, 0x41, 0x3D, 0x22, 0x5B, 0x54, 0x56, 0x34, 0x58, 0x13, 0x2F, 0x40, 0x2C,
        0x2E, 0x12, 0x0D, 0x11, 0x20, 0x38, 0x4E, 0x5C, 0x50, 0x2A, 0x39, 0x0F, 0x55, 0x53, 0x51, 0x22,
        0x58, 0x1F, 0x0B, 0x0B, 0x3A, 0x2D, 0x23, 0x5A, 0x01, 0x13, 0x5F, 0x40, 0x45, 0x2C, 0x01,
    )

    /** 运行时还原，避免完整密钥以单条明文常量进入常量池 */
    private fun decode(src: IntArray): String {
        val chars = CharArray(src.size)
        for (i in src.indices) chars[i] = (src[i] xor KEY_MASK).toChar()
        return String(chars)
    }

    /** 每次访问都重新还原：不把完整密钥长期驻留为单条 String 常量 */
    val API_KEY: String get() = decode(API_KEY_XOR)
    val SECRET_KEY: String get() = decode(SECRET_KEY_XOR)
}

/** 每请求随机 nonce */
fun picaNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

// 注：原 ImageQuality / PicaMethod 枚举全项目无使用点（image-quality 头是硬编码 "original"），
// 已按死代码清理移除，避免"看似有该能力、其实没接"。

enum class ComicSortType(val title: String) {
    DD("新到旧"),
    DA("旧到新"),
    LD("最多喜欢"),
    VD("最多观看"),
}

enum class ComicRankType {
    H24,
    D7,
    D30,
}

/** 默认请求头 */
fun defaultPicaHeaders(appUuid: String): Map<String, String> = mapOf(
    "accept" to "application/vnd.picacomic.com.v1+json",
    "User-Agent" to "okhttp/3.8.1",
    "Content-Type" to "application/json; charset=UTF-8",
    "api-key" to PicaConfig.API_KEY,
    "app-build-version" to "44",
    "app-platform" to "android",
    "app-uuid" to appUuid,
    "app-version" to "2.2.1.2.3.3",
    "app-channel" to "1",
)

/** HMAC-SHA256 签名 */
fun picaSignature(url: String, timestamp: String, nonce: String, method: String): String {
    val key = (url + timestamp + nonce + method + PicaConfig.API_KEY).lowercase()
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(PicaConfig.SECRET_KEY.toByteArray(), "HmacSHA256"))
    return mac.doFinal(key.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun picaTimestamp(): String = (System.currentTimeMillis() / 1000).toString()
