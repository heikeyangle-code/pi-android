package app.pi.ui.render

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    inlineCodeBackground = palette.borderMuted.copy(alpha = if (darkTheme) 0.42f else 0.34f),
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
        letterSpacing = if (profile.proseFont == PiTypographyProfile.FONT_MONO || profile.isClassic) 0.sp else 0.15.sp,
    )
    val mono = profile.styles.mono
    val code = profile.styles.code
    val heading = base.copy(color = palette.mdHeading, fontWeight = FontWeight.SemiBold)
    val body = textColor ?: palette.text

    return PiMarkdownTypography(
        h1 = heading.headingSlot(profile.h1, weight = FontWeight.Bold, letterSpacingSp = -0.35f),
        h2 = heading.headingSlot(profile.h2, weight = FontWeight.Bold, letterSpacingSp = -0.25f),
        h3 = heading.headingSlot(profile.h3, weight = FontWeight.SemiBold, letterSpacingSp = -0.15f),
        h4 = heading.headingSlot(profile.h4, weight = FontWeight.SemiBold, letterSpacingSp = -0.05f),
        h5 = heading.headingSlot(profile.h5, weight = FontWeight.SemiBold, letterSpacingSp = 0f),
        h6 = heading.headingSlot(profile.h6, weight = FontWeight.Medium, letterSpacingSp = 0.2f),
        alertTitle = heading.headingSlot(profile.h4, weight = FontWeight.SemiBold, letterSpacingSp = 0.1f),
        text = base.copy(color = body),
        code = code.copy(color = palette.mdCodeBlock),
        inlineCode = mono.copy(
            fontSize = profile.inlineCodeSizeSp.sp,
            lineHeight = profile.inlineCodeLineHeightSp.sp,
            fontWeight = profile.inlineCodeWeight,
            color = palette.mdCode,
            letterSpacing = 0.sp,
        ),
        quote = base.copy(color = palette.mdQuote),
        paragraph = if (monoParagraph) mono.copy(color = body) else base.copy(color = body),
        ordered = base.copy(color = body),
        bullet = if (profile.isClassic) {
            base.copy(color = palette.mdListBullet, fontFamily = FontFamily.Default)
        } else {
            base.copy(
                color = palette.mdListBullet,
                fontFamily = FontFamily.Default,
                fontSize = (base.fontSize.value * 1.08f).sp,
                lineHeight = base.lineHeight,
            )
        },
        list = base.copy(color = body),
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = palette.mdLink,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        table = base.copy(
            fontSize = profile.tableSizeSp.sp,
            lineHeight = profile.tableLineHeightSp.sp,
            color = body,
        ),
    )
}

internal fun piMarkdownSpacing(profile: PiTypographyProfile): PiMarkdownSpacing {
    val itemGap = profile.markdownListItemTop + profile.markdownListItemBottom
    return if (profile.isClassic) {
        PiMarkdownSpacing(
            block = profile.markdownBlock,
            listItem = itemGap,
            listIndent = profile.markdownListIndent,
            headingTopH1 = 0.dp,
            headingTopH2 = 0.dp,
            headingTopH3 = 0.dp,
            headingTopSmall = 0.dp,
        )
    } else {
        PiMarkdownSpacing(
            block = profile.markdownBlock,
            listItem = itemGap,
            listIndent = profile.markdownListIndent,
            headingTopH1 = 14.dp,
            headingTopH2 = 10.dp,
            headingTopH3 = 6.dp,
            headingTopSmall = 4.dp,
        )
    }
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
)
