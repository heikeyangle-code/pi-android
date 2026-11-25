package app.pi.ui.render

import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiGeistFamily
import app.pi.ui.theme.PiGeistMonoFamily
import app.pi.ui.theme.PiInterFamily
import app.pi.ui.theme.PiJetBrainsMonoFamily
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiTypographyProfile
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PiTypographyAndSpacingTest {

    @Test
    fun `optical spacing inserts hair space at CJK and Latin or digit boundaries`() {
        assertEquals(
            "使用\u200APiAndroid\u200A运行\u200A35\u200A种语言",
            insertCjkLatinOpticalSpacing("使用PiAndroid运行35种语言"),
        )
        assertEquals(
            "覆盖率\u200A100%\u200A通过",
            insertCjkLatinOpticalSpacing("覆盖率100%通过"),
        )
    }

    @Test
    fun `optical spacing leaves already spaced text, CJK punctuation, and pure Latin untouched`() {
        val pureEnglish = "Hello World 12345!"
        assertSame(pureEnglish, insertCjkLatinOpticalSpacing(pureEnglish))

        val alreadySpaced = "使用 Pi Android 运行 35 种语言"
        assertSame(alreadySpaced, insertCjkLatinOpticalSpacing(alreadySpaced))

        val cjkPunct = "标题：中文（测试），结束。"
        assertSame(cjkPunct, insertCjkLatinOpticalSpacing(cjkPunct))

        val punctuationNextToLatin = "支持，Kotlin和Java。"
        assertEquals("支持，Kotlin\u200A和\u200AJava。", insertCjkLatinOpticalSpacing(punctuationNextToLatin))
    }

    @Test
    fun `heading script detection tightens Latin headings without squeezing CJK`() {
        assertTrue(isLatinDominantHeading("System Architecture"))
        assertTrue(isLatinDominantHeading("Pi Android v0.2.1"))
        assertFalse(isLatinDominantHeading("系统架构设计"))
        assertFalse(isLatinDominantHeading("Pi Android 排版架构"))
        assertFalse(isLatinDominantHeading("12345"))
    }

    @Test
    fun `typography profile resolves all five reading fonts and two code fonts`() {
        val inter = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "sans", codeFont = "jetbrains", codeLigatures = true)
        assertEquals(PiInterFamily, inter.uiFontFamily())
        assertEquals(PiInterFamily, inter.proseFontFamily())
        assertEquals(PiJetBrainsMonoFamily, inter.codeFontFamily())
        assertEquals(PiFontFeatures.CODE_LIGATURES_ON, inter.codeFontFeatures(allowLigatures = true))
        assertEquals(PiFontFeatures.CODE_LIGATURES_OFF, inter.codeFontFeatures(allowLigatures = false))

        val geist = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "geist", codeFont = "geist_mono", codeLigatures = false)
        assertEquals(PiGeistFamily, geist.uiFontFamily())
        assertEquals(PiGeistFamily, geist.proseFontFamily())
        assertEquals(PiGeistMonoFamily, geist.codeFontFamily())
        assertEquals(PiFontFeatures.CODE_LIGATURES_OFF, geist.codeFontFeatures(allowLigatures = true))

        val serif = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "serif")
        assertEquals(PiInterFamily, serif.uiFontFamily())
        assertEquals(PiProseFamily, serif.proseFontFamily())

        val mono = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "mono", codeFont = "geist_mono")
        assertEquals(PiInterFamily, mono.uiFontFamily())
        assertEquals(PiGeistMonoFamily, mono.proseFontFamily())

        val system = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "system")
        assertEquals(FontFamily.Default, system.uiFontFamily())
        assertEquals(FontFamily.Default, system.proseFontFamily())
    }

    @Test
    fun `thinking typography profile scales prose and headings down cleanly`() {
        val base = PiTypographyProfile.forPreset("comfortable", "normal", "normal", proseFont = "geist", codeFont = "geist_mono")
        val thinking = base.forThinking()
        assertTrue(thinking.styles.prose.fontSize.value < base.styles.prose.fontSize.value)
        assertTrue(thinking.h1.sizeSp < base.h1.sizeSp)
        assertEquals(base.proseFont, thinking.proseFont)
        assertEquals(base.codeFont, thinking.codeFont)
    }
}
