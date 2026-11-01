package com.pika.core.model

import java.text.Normalizer

/**
 * 署名片段在署名中的角色。
 *
 * 角色只用于展示标签，不影响该片段能否被选中搜索——译者/汉化组同样可能是用户想找的人。
 */
enum class AuthorRole(val label: String?) {
    /** 默认作者，不显示标签 */
    AUTHOR(null),
    ORIGINAL("原作"),
    ART("作画"),
    SCRIPT("脚本"),
    TRANSLATOR("翻译"),
    LOCALIZER("汉化"),
    OTHER("其他"),
}

/** 一段可被单独搜索的署名 */
data class AuthorSegment(
    val text: String,
    val role: AuthorRole = AuthorRole.AUTHOR,
)

/**
 * 作者署名解析：把服务端一段自由文本的作者字段拆成可选择的片段。
 *
 * 解析只做「候选拆分」，不追求百分百正确：UI 永远同时提供完整署名与手动编辑出口。
 * 纯 JVM 实现（仅依赖 java.text.Normalizer），可直接单测。
 */
object AuthorNameParser {

    /** 片段数量上限：超过时只保留前 6 段，完整署名由 UI 兜底 */
    private const val MAX_SEGMENTS = 6

    private val TRIM_CHARS = charArrayOf(
        ' ', '\u3000', ',', '，', ';', '；', '/', '&', '|', '+', '\\', '、',
        '(', ')', '[', ']', ':', '：', '-', '—', '\n', '\r', '\t',
    )

    /** 分隔符：逗号/斜杠/&/分号/竖线/加号/顿号/反斜杠/换行，以及两侧带空格的 x 或 × */
    private val SEPARATORS = Regex("""[,/&;|+、\\\r\n]+|\s+[xX×]\s+""")

    private val BRACKETS = Regex("""\(([^)]*)\)|\[([^\]]*)\]""")

    private val CJK_ROLE_WORDS = listOf(
        "汉化组", "原作", "原著", "作画", "编剧", "脚本", "构成", "监修",
        "翻译", "译者", "汉化", "写真", "出品", "制作", "作者", "著", "绘", "画",
    )

    private val LATIN_ROLE_WORDS = listOf(
        "illustrator", "translator", "translation", "letterer", "original",
        "artist", "author", "writer", "script", "story", "editor", "art",
    )

    private val CJK_ROLE_PREFIX = Regex(
        """^(${CJK_ROLE_WORDS.joinToString("|")})(?:\s*[:：]\s*|\s+)(.+)$"""
    )
    private val CJK_ROLE_SUFFIX = Regex(
        """^(.+?)(?:\s*[:：]\s*|\s+)(${CJK_ROLE_WORDS.joinToString("|")})$"""
    )
    // 西文角色词只认冒号形式，避免把 "Original Sin" 这类名字误拆成角色 + 名字
    private val LATIN_ROLE_PREFIX = Regex(
        """^(${LATIN_ROLE_WORDS.joinToString("|")})\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LATIN_ROLE_SUFFIX = Regex(
        """^(.+?)\s*:\s*(${LATIN_ROLE_WORDS.joinToString("|")})$""",
        RegexOption.IGNORE_CASE,
    )

    private val DROP_WORDS = setOf("等", "等等", "etc", "etc.", "未知", "不明")

    fun parse(raw: String): List<AuthorSegment> {
        if (raw.isBlank()) return emptyList()
        val normalized = normalizeRaw(raw)
        if (normalized.isBlank()) return emptyList()

        val out = mutableListOf<AuthorSegment>()
        var cursor = 0
        for (match in BRACKETS.findAll(normalized)) {
            addPlain(normalized.substring(cursor, match.range.first), out)
            val content = match.groupValues[1].ifBlank { match.groupValues[2] }
            addBracketContent(content, out)
            cursor = match.range.last + 1
        }
        addPlain(normalized.substring(cursor), out)
        return dedupeAndCap(out)
    }

    /** 完整署名或单个片段：解析为空时回退为原串，保证永远有可搜索的文本 */
    fun segmentsOrSelf(raw: String): List<AuthorSegment> {
        val parsed = parse(raw)
        return parsed.ifEmpty { listOf(AuthorSegment(raw.trim())) }
    }

    fun isCompound(raw: String): Boolean = parse(raw).size > 1

    private fun normalizeRaw(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun addPlain(text: String, out: MutableList<AuthorSegment>) {
        for (rawPart in text.split(SEPARATORS)) {
            var part = rawPart.trim(*TRIM_CHARS)
            if (part.isBlank()) continue

            val (prefixRole, afterPrefix) = splitRolePrefix(part)
            part = afterPrefix.trim(*TRIM_CHARS)
            if (part.isBlank()) continue

            val (suffixRole, afterSuffix) = splitRoleSuffix(part)
            if (afterSuffix.isNotBlank()) part = afterSuffix

            // 整个片段只是一个角色词（如「张三 / 翻译」缺少译者名）：丢弃，避免把角色当人名
            if (roleOfWord(part) != null) continue
            if (DROP_WORDS.any { it.equals(part, ignoreCase = true) }) continue

            out += AuthorSegment(
                text = part,
                role = prefixRole ?: suffixRole ?: AuthorRole.AUTHOR,
            )
        }
    }

    private fun addBracketContent(content: String, out: MutableList<AuthorSegment>) {
        // 嵌套括号（如「张三（原作（原案））」）在正则里只能匹配到第一层，
        // 先把残留括号换成空格，避免 "(原案" 这种碎片进入片段
        val safe = content.replace(Regex("[()\\[\\]]"), " ")
        val cleaned = safe.trim(*TRIM_CHARS)
        if (cleaned.isBlank()) return

        // 「张三（翻译）」：括号里只有角色词 → 附着到前一个片段
        val roleOnly = roleOfWord(cleaned)
        if (roleOnly != null) {
            if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(role = roleOnly)
            return
        }

        val before = out.size
        addPlain(safe, out)
        if (out.size == before) return

        // 括号内是纯名字、且没有显式角色时，标为「其他」，不要冒充默认作者
        val hasExplicitRole = (before until out.size).any { out[it].role != AuthorRole.AUTHOR }
        if (!hasExplicitRole) {
            for (i in before until out.size) {
                out[i] = out[i].copy(role = AuthorRole.OTHER)
            }
        }
    }

    private fun splitRolePrefix(part: String): Pair<AuthorRole?, String> {
        CJK_ROLE_PREFIX.find(part)?.let { m ->
            return roleOfWord(m.groupValues[1]) to m.groupValues[2]
        }
        LATIN_ROLE_PREFIX.find(part)?.let { m ->
            return roleOfWord(m.groupValues[1]) to m.groupValues[2]
        }
        return null to part
    }

    private fun splitRoleSuffix(part: String): Pair<AuthorRole?, String> {
        CJK_ROLE_SUFFIX.find(part)?.let { m ->
            return roleOfWord(m.groupValues[2]) to m.groupValues[1]
        }
        LATIN_ROLE_SUFFIX.find(part)?.let { m ->
            return roleOfWord(m.groupValues[2]) to m.groupValues[1]
        }
        return null to part
    }

    private fun roleOfWord(word: String): AuthorRole? = when (word.trim().trim(':', '：').lowercase()) {
        "原作", "原著", "original", "story" -> AuthorRole.ORIGINAL
        "作画", "绘", "画", "写真", "art", "artist", "illustrator" -> AuthorRole.ART
        "编剧", "脚本", "script", "writer" -> AuthorRole.SCRIPT
        "翻译", "译者", "translation", "translator" -> AuthorRole.TRANSLATOR
        "汉化", "汉化组" -> AuthorRole.LOCALIZER
        "监修", "构成", "制作", "出品", "editor", "letterer" -> AuthorRole.OTHER
        "著", "作者", "author" -> AuthorRole.AUTHOR
        else -> null
    }

    private fun dedupeAndCap(segments: List<AuthorSegment>): List<AuthorSegment> {
        val seen = HashSet<String>()
        val out = ArrayList<AuthorSegment>(segments.size)
        for (segment in segments) {
            val text = segment.text.trim()
            if (text.isBlank()) continue
            if (!seen.add(text.lowercase())) continue
            out += segment.copy(text = text)
            if (out.size >= MAX_SEGMENTS) break
        }
        return out
    }
}

/**
 * 「作者字段包含查询片段」判定。
 *
 * CJK 直接子串匹配；西文按词边界匹配，避免 `art` 命中 `Bart`。
 */
object AuthorMatcher {

    fun matches(author: String, query: String): Boolean {
        val q = normalizeForMatch(query)
        val a = normalizeForMatch(author)
        if (q.isEmpty() || a.isEmpty()) return false
        if (q.any { it.isCjk() }) return a.contains(q)
        val pattern = Regex("(?<![a-z0-9])" + Regex.escape(q) + "(?![a-z0-9])")
        return pattern.containsMatchIn(a)
    }

    private fun normalizeForMatch(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase()
            // 分隔符两侧空格不影响语义：兼容「张三 / 李四」与「张三/李四」两种写法
            .replace(Regex("\\s*([,/&;|+、])\\s*"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun Char.isCjk(): Boolean =
        code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF
}
