package com.pika.core.pica

/**
 * 哔咔网络层配置：移植自 haka_comic lib/network/utils.dart
 */
object PicaConfig {
    const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    const val SECRET_KEY = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
    const val NONCE = "4ce7a7aa759b40f794d189a88b84aba8"
}

enum class PicaApi(val host: String) {
    PICACOMIC("https://picaapi.picacomic.com/"),
    GO2778("https://picaapi.go2778.com/"),
}

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

/** 默认请求头（不含时间/签名/token 等动态项） */
fun defaultPicaHeaders(): Map<String, String> = mapOf(
    "accept" to "application/vnd.picacomic.com.v1+json",
    "User-Agent" to "okhttp/3.8.1",
    "Content-Type" to "application/json; charset=UTF-8",
    "api-key" to PicaConfig.API_KEY,
    "app-build-version" to "45",
    "app-platform" to "android",
    "app-uuid" to "defaultUuid",
    "app-version" to "2.2.1.3.3.4",
    "nonce" to PicaConfig.NONCE,
    "app-channel" to "1",
)

/** HMAC-SHA256 签名：key = (url + timestamp + nonce + method + apiKey).lowercase() */
fun picaSignature(url: String, timestamp: String, nonce: String, method: String): String {
    val key = (url + timestamp + nonce + method + PicaConfig.API_KEY).lowercase()
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(PicaConfig.SECRET_KEY.toByteArray(), "HmacSHA256"))
    return mac.doFinal(key.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun picaTimestamp(): String = (System.currentTimeMillis() / 1000).toString()