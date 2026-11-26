package app.pi.ui.render

import androidx.compose.ui.text.font.FontFamily
import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiGeistMonoFamily
import app.pi.ui.theme.PiJetBrainsMonoFamily
import app.pi.ui.theme.PiPlexSansFamily
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiTypographyProfile
import app.pi.ui.theme.PiWenKaiFamily
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
    fun `whole-app CJK+Latin fonts stay strictly unified across UI and prose`() {
        val plex = PiTypographyProfile.build(16, PiTypographyProfile.COMFORTABLE, proseFont = "sans", codeFont = "jetbrains", codeLigatures = true)
        assertEquals(PiPlexSansFamily, plex.uiFontFamily())
        assertEquals(PiPlexSansFamily, plex.proseFontFamily())
        assertEquals(PiPlexSansFamily, plex.styles.prose.fontFamily)
        assertEquals(PiPlexSansFamily, plex.styles.meta.fontFamily)
        assertEquals(PiJetBrainsMonoFamily, plex.codeFontFamily())
        assertEquals(PiFontFeatures.CODE_LIGATURES_ON, plex.codeFontFeatures(allowLigatures = true))
        assertEquals(PiFontFeatures.CODE_LIGATURES_OFF, plex.codeFontFeatures(allowLigatures = false))

        val serif = PiTypographyProfile.build(16, PiTypographyProfile.COMFORTABLE, proseFont = "serif", codeFont = "geist_mono", codeLigatures = false)
        assertEquals(PiProseFamily, serif.uiFontFamily())
        assertEquals(PiProseFamily, serif.proseFontFamily())
        assertEquals(PiProseFamily, serif.styles.prose.fontFamily)
        assertEquals(PiProseFamily, serif.styles.meta.fontFamily)
        assertEquals(PiGeistMonoFamily, serif.codeFontFamily())
        assertEquals(PiFontFeatures.CODE_LIGATURES_OFF, serif.codeFontFeatures(allowLigatures = true))

        val kai = PiTypographyProfile.build(16, PiTypographyProfile.COMFORTABLE, proseFont = "kai")
        assertEquals(PiWenKaiFamily, kai.uiFontFamily())
        assertEquals(PiWenKaiFamily, kai.proseFontFamily())
        assertEquals(PiWenKaiFamily, kai.styles.prose.fontFamily)
        assertEquals(PiWenKaiFamily, kai.styles.meta.fontFamily)

        val system = PiTypographyProfile.build(16, PiTypographyProfile.COMFORTABLE, proseFont = "system")
        assertEquals(FontFamily.Default, system.uiFontFamily())
        assertEquals(FontFamily.Default, system.proseFontFamily())
        assertEquals(FontFamily.Default, system.styles.prose.fontFamily)
        assertEquals(FontFamily.Default, system.styles.meta.fontFamily)
    }

    @Test
    fun `text size and reading density axes are strictly orthogonal across all 15 combinations`() {
        val densities = listOf(PiTypographyProfile.COMPACT, PiTypographyProfile.COMFORTABLE, PiTypographyProfile.RELAXED)
        for (size in PiTypographyProfile.TEXT_SIZE_STOPS) {
            val profiles = densities.map { PiTypographyProfile.build(textSizeSp = size, density = it) }
            // Changing density must NEVER alter font sizes or heading sizes.
            val (compact, comfortable, relaxed) = profiles
            assertEquals(size.toFloat(), compact.styles.prose.fontSize.value, 0.001f)
            assertEquals(compact.styles.prose.fontSize, comfortable.styles.prose.fontSize)
            assertEquals(comfortable.styles.prose.fontSize, relaxed.styles.prose.fontSize)
            assertEquals(compact.styles.code.fontSize, relaxed.styles.code.fontSize)
            assertEquals(compact.h1.sizeSp, relaxed.h1.sizeSp)
            assertEquals(compact.tableSizeSp, relaxed.tableSizeSp, 0.001f)

            // Density must strictly widen vertical rhythm & leading at EVERY size (zero silent drops).
            assertTrue(compact.styles.prose.lineHeight.value < comfortable.styles.prose.lineHeight.value)
            assertTrue(comfortable.styles.prose.lineHeight.value < relaxed.styles.prose.lineHeight.value)
            assertTrue(compact.styles.code.lineHeight.value < comfortable.styles.code.lineHeight.value)
            assertTrue(comfortable.styles.code.lineHeight.value < relaxed.styles.code.lineHeight.value)
            assertTrue(compact.markdownBlock < comfortable.markdownBlock)
            assertTrue(comfortable.markdownBlock < relaxed.markdownBlock)
            assertTrue(compact.messageSpacing < comfortable.messageSpacing)
            assertTrue(comfortable.messageSpacing < relaxed.messageSpacing)

            // Modular scale invariants: strictly monotonic headings and readable code floor (>= 12.5sp).
            for (p in profiles) {
                assertTrue(p.styles.code.fontSize.value >= 12.5f)
                assertTrue(p.h1.sizeSp > p.h2.sizeSp)
                assertTrue(p.h2.sizeSp > p.h3.sizeSp)
                assertTrue(p.h3.sizeSp > p.h4.sizeSp)
                assertTrue(p.h4.sizeSp > p.h5.sizeSp)
                assertTrue(p.h5.sizeSp > p.h6.sizeSp)
                assertEquals(size, p.h6.sizeSp)
            }
        }

        // Changing text size must NEVER alter density spacing tokens.
        val small = PiTypographyProfile.build(textSizeSp = 14, density = PiTypographyProfile.COMFORTABLE)
        val large = PiTypographyProfile.build(textSizeSp = 18, density = PiTypographyProfile.COMFORTABLE)
        assertEquals(small.markdownBlock, large.markdownBlock)
        assertEquals(small.messageSpacing, large.messageSpacing)
        assertEquals(small.blockSpacing, large.blockSpacing)
        assertEquals(small.headingTopH1, large.headingTopH1)
    }

    @Test
    fun `thinking typography profile scales prose and headings down cleanly`() {
        val base = PiTypographyProfile.build(16, PiTypographyProfile.COMFORTABLE, proseFont = "kai", codeFont = "geist_mono")
        val thinking = base.forThinking()
        assertTrue(thinking.styles.prose.fontSize.value < base.styles.prose.fontSize.value)
        assertTrue(thinking.h1.sizeSp < base.h1.sizeSp)
        assertEquals(base.proseFont, thinking.proseFont)
        assertEquals(base.codeFont, thinking.codeFont)
    }
}
