package com.pika.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 禁漫（jmcomic）移动端 API 响应模型（实测字段，2026-09-03）。
 *
 * 约定：所有响应外层都是 {code, errorMsg, data}，JmClient 会把加密的 data 解密后
 * 替换回原位置再交给 kotlinx 解析，因此这里的 data 字段就是解密后的明文结构。
 * 字段名以本会话对真实 /album、/categories/filter、/search、/chapter、/forum 的
 * 实机探测为准（非社区库旧版字段）。
 */

/** 响应信封：code + errorMsg + data(解密后明文) */
interface JmEnvelope {
    val code: Int
    val errorMsg: String?
}

// ---------- 分类 ----------

@Serializable
data class JmCategoriesResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmCategoriesData = JmCategoriesData(),
) : JmEnvelope

@Serializable
data class JmCategoriesData(
    val categories: List<JmCategory> = emptyList(),
)

@Serializable
data class JmCategory(
    val id: String = "",
    val name: String = "",
    /** 用作筛选参数 c= 的取值（优先于 id） */
    val slug: String = "",
    @SerialName("total_albums") val totalAlbums: Int = 0,
    @SerialName("sub_categories") val subCategories: List<JmSubCategory> = emptyList(),
)

@Serializable
data class JmSubCategory(
    val name: String = "",
    val slug: String = "",
)

// ---------- 列表（浏览 / 搜索 / 排行） ----------

@Serializable
data class JmListResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmListData = JmListData(),
) : JmEnvelope

@Serializable
data class JmListData(
    val content: List<JmAlbumSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class JmAlbumSummary(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /** 分类名（展示用） */
    val category: String = "",
    @SerialName("category_sub") val categorySub: String = "",
    val liked: Boolean = false,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("update_at") val updateAt: Long = 0,
    /** 发布日期字符串，如 2026-08-28 */
    val adddate: String = "",
)

// ---------- 详情 ----------

@Serializable
data class JmAlbumResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmAlbumDetail = JmAlbumDetail(),
) : JmEnvelope

@Serializable
data class JmAlbumDetail(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    /** 详情接口不返回图片，仅章节接口返回 */
    val images: List<String> = emptyList(),
    val addtime: String = "",
    @SerialName("total_views") val totalViews: Long = 0,
    @SerialName("total_photos") val totalPhotos: Int = 0,
    val likes: Long = 0,
    /** 章节列表（单章本子为空，需回退用 album id 自身作为 photo id） */
    val series: List<JmSeriesItem> = emptyList(),
    @SerialName("series_id") val seriesId: String = "",
    @SerialName("comment_total") val commentTotal: Int = 0,
    val tags: List<String> = emptyList(),
    val works: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    @SerialName("related_list") val relatedList: List<JmRelatedItem> = emptyList(),
)

@Serializable
data class JmSeriesItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class JmRelatedItem(
    val id: String = "",
    val name: String = "",
    val author: String = "",
)

// ---------- 章节图片 ----------

@Serializable
data class JmChapterResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmChapterData = JmChapterData(),
) : JmEnvelope

@Serializable
data class JmChapterData(
    val id: String = "",
    /** 图片文件名列表，如 ["00001.webp", "00002.webp"] */
    val images: List<String> = emptyList(),
)

// ---------- 评论 / 论坛 ----------

@Serializable
data class JmForumResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmForumData = JmForumData(),
) : JmEnvelope

@Serializable
data class JmForumData(
    val list: List<JmForumComment> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class JmForumComment(
    val id: String = "",
    val username: String = "",
    val nickname: String = "",
    val content: String = "",
    val addtime: String = "",
    /** 等级经验，如 "Lv.12 exp:3456" */
    val expinfo: String = "",
    val badges: List<String> = emptyList(),
)

// ---------- 每周必看 ----------

@Serializable
data class JmWeekResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmWeekData = JmWeekData(),
) : JmEnvelope

@Serializable
data class JmWeekData(
    val categories: List<JmWeekItem> = emptyList(),
)

@Serializable
data class JmWeekItem(
    val id: String = "",
    val time: String = "",
)

// ---------- 登录 ----------

@Serializable
data class JmLoginResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmLoginData = JmLoginData(),
) : JmEnvelope

@Serializable
data class JmLoginData(
    /** 会话密钥，后续请求需放入 Cookie: AVS={s} */
    val s: String = "",
    val uid: String = "",
)

// ---------- 收藏 / 签到（需登录） ----------

/** 收藏列表 / 云端历史：复用列表信封（content + total） */
/** 收藏切换 / 签到动作：仅需 code 判断成功，data 忽略 */
@Serializable
data class JmActionResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
) : JmEnvelope

@Serializable
data class JmDailyResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmDailyData? = null,
) : JmEnvelope

@Serializable
data class JmDailyData(
    @SerialName("is_check_in") val isCheckIn: Boolean = false,
    @SerialName("check_in_days") val checkInDays: Int = 0,
    /** 部分实现用 days / sign_days 表达连续天数 */
    val days: Int = 0,
    val message: String = "",
)
