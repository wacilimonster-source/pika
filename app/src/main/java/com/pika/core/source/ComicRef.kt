package com.pika.core.source

/**
 * 作品标识：`<数据源名>_<源内 id>`。
 *
 * 一本作品在 App 内的身份必须同时包含「哪个源」和「源内 id」：两源的 id 命名空间互不相干，
 * 而阅读进度、最近阅读、已读标记、更新时间缓存、下载与路由参数此前都只记裸 id，
 * 切换数据源后就会拿当前源去请求另一源的作品。
 *
 * 历史数据（无前缀的裸 id）一律按 [SourceType.PICACG] 解释：另一源在带前缀的键出现之前
 * 从未成功加载过任何作品。
 */
object ComicRef {

    /**
     * 分隔符取 `_`：两源的 id 字符集（哔咔 24 位十六进制 / 禁漫纯数字）都不含它，
     * 而它本身是 URI 安全字符，进路由参数不需要转义，不依赖解码往返。
     */
    private const val SEP = '_'

    /** 列表条目 / 详情的标识 */
    fun of(source: SourceType, comicId: String): String = "${source.name}${SEP}$comicId"

    /** 由持久化的源名（如 DownloadTask.source）拼标识；名字不认识时退回哔咔 */
    fun ofName(sourceName: String?, comicId: String): String {
        val type = SourceType.entries.firstOrNull { it.name == sourceName } ?: SourceType.PICACG
        return of(type, comicId)
    }

    /** 拆成（源, 源内 id） */
    fun parse(ref: String): Pair<SourceType, String> {
        val i = ref.indexOf(SEP)
        if (i <= 0) return SourceType.PICACG to ref
        val type = SourceType.entries.firstOrNull { it.name == ref.substring(0, i) } ?: SourceType.PICACG
        return type to ref.substring(i + 1)
    }

    /** 只要源内 id（调源接口、匹配下载目录等按 id 记账的场合） */
    fun id(ref: String): String = parse(ref).second

    fun source(ref: String): SourceType = parse(ref).first
}
