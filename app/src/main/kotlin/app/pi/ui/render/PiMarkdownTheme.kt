package app.pi.ui.render

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiHeading
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTypographyProfile

/**
 * Pure, theme-derived color tokens for Pi's native Markdown renderer.
 * Every single color originates from [PiPalette] so built-in dark/light and custom user themes
 * stay 100% coherent with zero hardcoded palette overrides.
 */
@Immutable
internal data class PiMarkdownColors(
    val text: Color,
    val mutedText: Color,
    val codeBackground: Color,
    val inlineCodeBackground: Color,
    val dividerColor: Color,
    val tableBackground: Color,
    val quoteText: Color,
    val quoteBorder: Color,
    val alertNote: Color,
    val alertTip: Color,
    val alertImportant: Color,
    val alertWarning: Color,
    val alertCaution: Color,
    val darkTheme: Boolean,
)

/**
 * Pure typography hierarchy for Pi's native Markdown renderer, built from [PiTypographyProfile]
 * and [PiPalette].
 */
@Immutable
internal data class PiMarkdownTypography(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val alertTitle: TextStyle,
    val text: TextStyle,
    val code: TextStyle,
    val inlineCode: TextStyle,
    val quote: TextStyle,
    val paragraph: TextStyle,
    val ordered: TextStyle,
    val bullet: TextStyle,
    val list: TextStyle,
    val textLink: TextLinkStyles,
    val table: TextStyle,
)

/**
 * Vertical rhythm & structural spacing derived from [PiTypographyProfile].
 */
@Immutable
internal data class PiMarkdownSpacing(
    val block: Dp,
    val listItem: Dp,
    val listIndent: Dp,
    val headingTopH1: Dp,
    val headingTopH2: Dp,
    val headingTopH3: Dp,
    val headingTopSmall: Dp,
)

internal fun piMarkdownColors(
    palette: PiPalette,
    darkTheme: Boolean,
    textColor: Color? = null,
): PiMarkdownColors = PiMarkdownColors(
    text = textColor ?: palette.text,
    mutedText = palette.muted,
    codeBackground = Color.Transparent,
    inlineCodeBackground = palette.borderMuted.copy(alpha = if (darkTheme) 0.36f else 0.28f),
    dividerColor = palette.mdHr.copy(alpha = 0.28f),
    tableBackground = palette.cardBg,
    quoteText = palette.mdQuote,
    quoteBorder = palette.mdQuoteBorder,
    alertNote = palette.accent,
    alertTip = palette.success,
    alertImportant = palette.mdHeading,
    alertWarning = palette.warning,
    alertCaution = palette.error,
    darkTheme = darkTheme,
)

internal fun piMarkdownTypography(
    palette: PiPalette,
    profile: PiTypographyProfile,
    textColor: Color? = null,
    monoParagraph: Boolean = false,
): PiMarkdownTypography {
    val base = profile.styles.prose.copy(
        fontFamily = profile.proseFontFamily(),
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        fontFeatureSettings = profile.proseFontFeatures(),
        lineBreak = LineBreak.Paragraph,
    )
    val mono = profile.styles.mono
    val code = profile.styles.code.copy(
        fontFamily = profile.codeFontFamily(),
        fontFeatureSettings = profile.codeFontFeatures(allowLigatures = true),
    )
    val heading = base.copy(
        color = palette.mdHeading,
        fontWeight = FontWeight.SemiBold,
        lineBreak = LineBreak.Heading,
    )
    val body = textColor ?: palette.text
    val tableFeatures = listOfNotNull(profile.proseFontFeatures(), PiFontFeatures.TABULAR).joinToString(", ")

    return PiMarkdownTypography(
        // Neutral 0sp default tracking on headings so CJK titles never get crushed;
        // Latin-dominant headings apply subtle optical tightening in PiMarkdownHeading.
        h1 = heading.headingSlot(profile.h1, weight = FontWeight.Bold),
        h2 = heading.headingSlot(profile.h2, weight = FontWeight.Bold),
        h3 = heading.headingSlot(profile.h3, weight = FontWeight.SemiBold),
        h4 = heading.headingSlot(profile.h4, weight = FontWeight.SemiBold),
        h5 = heading.headingSlot(profile.h5, weight = FontWeight.SemiBold),
        h6 = heading.headingSlot(profile.h6, weight = FontWeight.Medium),
        alertTitle = heading.headingSlot(profile.h4, weight = FontWeight.SemiBold),
        text = base.copy(color = body),
        code = code.copy(color = palette.mdCodeBlock),
        inlineCode = mono.copy(
            fontFamily = profile.codeFontFamily(),
            fontSize = profile.inlineCodeSizeSp.sp,
            lineHeight = profile.inlineCodeLineHeightSp.sp,
            fontWeight = profile.inlineCodeWeight,
            color = palette.mdCode,
            letterSpacing = 0.sp,
            fontFeatureSettings = profile.codeFontFeatures(allowLigatures = false),
        ),
        quote = base.copy(
            color = palette.mdQuote,
            fontSize = (base.fontSize.value * 0.96f).sp,
            lineHeight = (base.lineHeight.value * 0.96f).sp,
        ),
        paragraph = if (monoParagraph) mono.copy(color = body) else base.copy(color = body),
        ordered = base.copy(color = body, fontFeatureSettings = tableFeatures),
        bullet = base.copy(
            color = palette.mdListBullet,
            fontFamily = FontFamily.Default,
            fontSize = (base.fontSize.value * 1.08f).sp,
            lineHeight = base.lineHeight,
        ),
        list = base.copy(color = body),
        // Editorial link style: Medium weight + theme accent ink; subtle baseline-offset hairline
        // underline is drawn by PiInlineMarkdownText so descenders (g/p/y) and CJK bottoms stay clean.
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = palette.mdLink,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.None,
            ),
        ),
        table = base.copy(
            fontSize = profile.tableSizeSp.sp,
            lineHeight = profile.tableLineHeightSp.sp,
            color = body,
            fontFeatureSettings = tableFeatures,
        ),
    )
}

internal fun piMarkdownSpacing(profile: PiTypographyProfile): PiMarkdownSpacing {
    val itemGap = profile.markdownListItemTop + profile.markdownListItemBottom
    return PiMarkdownSpacing(
        block = profile.markdownBlock,
        listItem = itemGap,
        listIndent = profile.markdownListIndent,
        headingTopH1 = 16.dp,
        headingTopH2 = 12.dp,
        headingTopH3 = 8.dp,
        headingTopSmall = 4.dp,
    )
}

private fun TextStyle.headingSlot(
    slot: PiHeading,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacingSp: Float = 0f,
): TextStyle = copy(
    fontSize = slot.sizeSp.sp,
    lineHeight = slot.lineHeightSp.sp,
    fontWeight = weight,
    letterSpacing = letterSpacingSp.sp,
    lineBreak = LineBreak.Heading,
)
