package com.pika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 署名拆分与「署名包含片段」匹配的关键不变量。
 *
 * 解析器是纯函数，这里锁定产品设计稿 §5.4 的用例，避免后续调整分隔符时
 * 把「翻译/联合作者」这类最常见格式拆坏。
 */
class AuthorNameTest {

    private fun parse(raw: String) = AuthorNameParser.parse(raw)

    @Test
    fun `单一作者不拆分`() {
        assertEquals(listOf(AuthorSegment("张三")), parse("张三"))
    }

    @Test
    fun `斜杠与逗号联合作者`() {
        assertEquals(
            listOf(AuthorSegment("张三"), AuthorSegment("李四")),
            parse("张三 / 李四"),
        )
        assertEquals(
            listOf(AuthorSegment("张三"), AuthorSegment("李四")),
            parse("张三，李四"),
        )
    }

    @Test
    fun `括号中的翻译识别为独立片段`() {
        assertEquals(
            listOf(
                AuthorSegment("张三", AuthorRole.AUTHOR),
                AuthorSegment("王五", AuthorRole.TRANSLATOR),
            ),
            parse("张三（翻译：王五）"),
        )
    }

    @Test
    fun `角色词附着到前一个名字`() {
        assertEquals(
            listOf(
                AuthorSegment("张三", AuthorRole.AUTHOR),
                AuthorSegment("王五", AuthorRole.ART),
            ),
            parse("张三 (著) / 王五 (绘)"),
        )
    }

    @Test
    fun `前缀角色词`() {
        assertEquals(
            listOf(
                AuthorSegment("张三", AuthorRole.ORIGINAL),
                AuthorSegment("李四", AuthorRole.ART),
            ),
            parse("原作：张三 / 作画：李四"),
        )
    }

    @Test
    fun `后缀角色词`() {
        assertEquals(
            listOf(
                AuthorSegment("张三", AuthorRole.ORIGINAL),
                AuthorSegment("李四", AuthorRole.ART),
            ),
            parse("张三 原作、李四 作画"),
        )
    }

    @Test
    fun `汉化组识别为独立角色`() {
        assertEquals(
            listOf(
                AuthorSegment("张三", AuthorRole.AUTHOR),
                AuthorSegment("某某汉化组", AuthorRole.LOCALIZER),
            ),
            parse("张三 / 汉化：某某汉化组"),
        )
    }

    @Test
    fun `整体角色前缀被剥离`() {
        assertEquals(listOf(AuthorSegment("张三")), parse("作者：张三"))
    }

    @Test
    fun `点分隔的西文姓名不拆`() {
        assertEquals(listOf(AuthorSegment("J·K·罗琳")), parse("J·K·罗琳"))
    }

    @Test
    fun `空格分隔的西文姓名不拆`() {
        assertEquals(listOf(AuthorSegment("Mary Jane")), parse("Mary Jane"))
    }

    @Test
    fun `两侧带空格的 x 作为联合作者分隔符`() {
        assertEquals(
            listOf(AuthorSegment("张三"), AuthorSegment("李四")),
            parse("张三 x 李四"),
        )
    }

    @Test
    fun `连续与首尾分隔符不产生空片段`() {
        assertEquals(
            listOf(AuthorSegment("张三"), AuthorSegment("李四")),
            parse("张三 // 李四"),
        )
        assertEquals(listOf(AuthorSegment("张三")), parse("/张三/"))
    }

    @Test
    fun `嵌套括号不产生括号碎片`() {
        val segments = parse("张三（原作（原案））")
        assertEquals("张三", segments.first().text)
        assertTrue(segments.all { !it.text.contains('(') && !it.text.contains(')') })
    }

    @Test
    fun `超过六段时截断`() {
        val segments = parse("张三/李四/王五/赵六/钱七/孙八/周九")
        assertEquals(6, segments.size)
    }

    @Test
    fun `空白署名返回空列表`() {
        assertTrue(parse("   ").isEmpty())
    }

    @Test
    fun `完整署名包含片段时命中`() {
        assertTrue(AuthorMatcher.matches("张三 / 李四", "张三"))
        assertTrue(AuthorMatcher.matches("张三 / 李四", "李四"))
        assertTrue(AuthorMatcher.matches("王小明", "小明"))
    }

    @Test
    fun `西文按词边界匹配避免误命中`() {
        assertFalse(AuthorMatcher.matches("Bart", "art"))
        assertTrue(AuthorMatcher.matches("Art / Bart", "Art"))
        assertTrue(AuthorMatcher.matches("J.K. Rowling", "Rowling"))
    }

    @Test
    fun `分隔符两侧空格不影响命中`() {
        assertTrue(AuthorMatcher.matches("张三/李四", "张三 / 李四"))
        assertTrue(AuthorMatcher.matches("张三 / 李四", "张三/李四"))
    }

    @Test
    fun `空白查询不命中`() {
        assertFalse(AuthorMatcher.matches("张三", "  "))
    }
}
