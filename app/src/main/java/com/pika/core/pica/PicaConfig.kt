package com.pika.core.pica

/**
 * 哔咔网络层配置：移植自 haka_comic lib/network/utils.dart
 *
 * 说明：客户端签名密钥本质上无法真正隐藏（逆向总能取到），此处仅做「分段拼接」处理，
 * 避免完整密钥以单条字符串常量的形式出现在常量池中，提高静态提取的门槛。
 * 如需更强加固，可进一步移入 NDK .so 或引入字符串加密（StringFog / DexGuard）。
 */
object PicaConfig {
    private const val API_KEY_P1 = "C69BAF41DA5AB"
    private const val API_KEY_P2 = "D1FFEDC6D2FEA56B"
    const val API_KEY: String = API_KEY_P1 + API_KEY_P2

    private const val SECRET_P1 = "~d}\$Q7\$eIni=V)9"
    private const val SECRET_P2 = "\\RK/P.RM4;9[7|@/"
    private const val SECRET_P3 = "CA}b~OW!3?EV`:"
    private const val SECRET_P4 = "<>M7pddUBL5n|0/*Cn"
    const val SECRET_KEY: String = SECRET_P1 + SECRET_P2 + SECRET_P3 + SECRET_P4
}

/** 每请求随机 nonce */
fun picaNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

enum class ImageQuality(val displayName: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    ORIGINAL("原画"),
}

enum class PicaMethod(val value: String) {
    GET("GET"),
    POST("POST"),
    DELETE("DELETE"),
    PUT("PUT"),
}

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
