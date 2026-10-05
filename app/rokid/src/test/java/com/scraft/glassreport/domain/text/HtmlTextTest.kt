package com.scraft.glassreport.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Graph の実データに出る形をそのまま食わせる。ここが崩れると Feed の本文が読めなくなる。 */
class HtmlTextTest {
    @Test
    fun stripsTagsFromTheShapeGraphActuallyReturns() {
        val html = "<div>これはテストです</div><div><img src=\"../hostedContents/1/\$value\"></div>"

        assertEquals("これはテストです", HtmlText.toPlainText(html))
    }

    @Test
    fun blockTagsBecomeLineBreaksSoLinesDoNotRunTogether() {
        val html = "<div>配管の水漏れ</div><div>応急処置しました</div>"

        assertEquals("配管の水漏れ\n応急処置しました", HtmlText.toPlainText(html))
    }

    @Test
    fun brBecomesLineBreak() {
        assertEquals("上段\n下段", HtmlText.toPlainText("<p>上段<br>下段</p>"))
    }

    @Test
    fun inlineTagsAreRemovedWithoutSplittingTheLine() {
        assertEquals("至急 確認してください", HtmlText.toPlainText("<div><b>至急</b> <span>確認してください</span></div>"))
    }

    @Test
    fun entitiesAreRestored() {
        val html = "<div>圧力 &amp; 温度 &lt;要確認&gt; &quot;A棟&quot; &#39;1F&#39; &#x30C6;&nbsp;OK</div>"

        assertEquals("圧力 & 温度 <要確認> \"A棟\" '1F' テ OK", HtmlText.toPlainText(html))
    }

    @Test
    fun entitiesAreRestoredOnlyOnceSoEscapedMarkupSurvives() {
        // 「&lt;」を書きたくて &amp;lt; と二重に書かれた本文。1 段だけ戻して <b> をタグと誤認しない。
        assertEquals("&lt;b&gt;", HtmlText.toPlainText("<div>&amp;lt;b&amp;gt;</div>"))
    }

    @Test
    fun unknownEntityIsLeftAsWritten() {
        assertEquals("&unknown; 残す", HtmlText.toPlainText("<div>&unknown; 残す</div>"))
    }

    @Test
    fun collapsesWhitespaceAndDropsBlankLines() {
        val html = "<div>  空白   多め  </div><div>　</div><div>\n二行目\n</div>"

        assertEquals("空白 多め\n二行目", HtmlText.toPlainText(html))
    }

    @Test
    fun attachmentOnlyBodyBecomesEmpty() {
        assertEquals("", HtmlText.toPlainText("<attachment id=\"abc\"></attachment>"))
        assertEquals("", HtmlText.toPlainText(null))
        assertEquals("", HtmlText.toPlainText(""))
    }

    @Test
    fun countsImagesWithoutRenderingThem() {
        val html = "<div>写真</div><div><img src=\"a\"><IMG src=\"b\"/></div>"

        assertEquals(2, HtmlText.imageCount(html))
        assertEquals(0, HtmlText.imageCount("<div>文だけ</div>"))
        assertEquals(0, HtmlText.imageCount(null))
    }
}
