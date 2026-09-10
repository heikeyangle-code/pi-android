package app.pi.ui.render

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

/**
 * pi's markdown tokens, mapped onto the renderer's colour slots.
 *
 * pi draws markdown in a terminal, so its `MarkdownTheme` has exactly ten colour
 * hooks (`packages/coding-agent/src/modes/interactive/theme/theme.ts`,
 * `getMarkdownTheme`). The renderer here has five colour slots plus typography,
 * so the mapping is not one-to-one, and the places where it cannot be are called
 * out rather than papered over:
 *
 * | pi token            | goes to                                              |
 * |---------------------|------------------------------------------------------|
 * | `mdHeading`         | colour of every heading style                        |
 * | `mdLink`            | `TextLinkStyles.style`                               |
 * | `mdLinkUrl`         | not renderable — a terminal prints it in parentheses, a phone shows no URL text. Still resolvable for the link sheet |
 * | `mdCode`            | `inlineCode` style colour                            |
 * | `mdCodeBlock`       | `code` style colour, i.e. the fallback body colour    |
 * | `mdCodeBlockBorder` | the code block's border stroke                       |
 * | `mdQuote`           | `quote` style colour (and the quote bar — see below)  |
 * | `mdQuoteBorder`     | the quote bar, which shares the quote colour          |
 * | `mdHr`              | `dividerColor`                                       |
 * | `mdListBullet`      | `bullet` style colour                                |
 *
 * The quote bar is the one genuine compromise: the renderer's block quote paints
 * its bar and its text from a single colour, where pi has two tokens. Both of
 * pi's built-in themes set `mdQuote` and `mdQuoteBorder` to the same value, so
 * for the shipped themes the output is identical; a hand-written pi theme that
 * chooses two different values loses the distinction.
 */
@Composable
internal fun piMarkdownColors(): MarkdownColors {
    val palette = PiTheme.palette
    return markdownColor(
        text = palette.text,
        codeBackground = palette.cardBg,
        inlineCodeBackground = palette.infoBg,
        dividerColor = palette.mdHr,
        tableBackground = palette.cardBg,
    )
}

/**
 * pi's markdown type hierarchy, expressed in pi's colours.
 *
 * A terminal has one font and no size scale, so pi's headings differ only by
 * weight and underline. The app keeps the *colour* faithful and adds the size
 * scale every Compose surface already has — a heading in a phone-sized column
 * that is the same size as body text is unreadable, and pi's own HTML export
 * makes the same trade for the same reason.
 */
@Composable
internal fun piMarkdownTypography(): MarkdownTypography {
    val palette = PiTheme.palette
    val base = MaterialTheme.typography.bodyLarge
    val mono = PiTheme.text.mono
    val heading = base.copy(color = palette.mdHeading, fontWeight = FontWeight.SemiBold)

    return markdownTypography(
        h1 = heading.copy(
            fontSize = 22.sp,
            lineHeight = 30.sp,
            textDecoration = TextDecoration.Underline,
        ),
        h2 = heading.copy(fontSize = 20.sp, lineHeight = 28.sp),
        h3 = heading.copy(fontSize = 18.sp, lineHeight = 26.sp),
        h4 = heading.copy(fontSize = 16.sp, lineHeight = 24.sp),
        h5 = heading.copy(fontSize = 15.sp, lineHeight = 22.sp),
        h6 = heading.copy(fontSize = 14.sp, lineHeight = 21.sp),
        text = base.copy(color = palette.text),
        code = mono.copy(color = palette.mdCodeBlock),
        inlineCode = mono.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = palette.mdCode),
        quote = base.copy(color = palette.mdQuote),
        paragraph = base.copy(color = palette.text),
        ordered = base.copy(color = palette.text),
        bullet = base.copy(color = palette.mdListBullet),
        list = base.copy(color = palette.text),
        textLink = TextLinkStyles(
            style = SpanStyle(color = palette.mdLink, textDecoration = TextDecoration.Underline),
        ),
        table = mono.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = palette.text),
    )
}

/**
 * pi's rhythm, as far as the renderer exposes it: block spacing stays tight so a
 * long answer does not become a column of whitespace, and the code/quote inset
 * is the same [PiSpacing.card] every other card in the app uses.
 */
@Composable
internal fun piMarkdownPadding(): MarkdownPadding = markdownPadding(
    block = 2.dp,
    list = 4.dp,
    listItemTop = 2.dp,
    listItemBottom = 2.dp,
    listIndent = 12.dp,
    codeBlock = PaddingValues(horizontal = PiSpacing.card, vertical = 10.dp),
    blockQuote = PaddingValues(horizontal = PiSpacing.card, vertical = 0.dp),
    blockQuoteText = PaddingValues(vertical = 4.dp),
)

/** 12dp corners keep code blocks recognisably cards rather than slabs. */
@Composable
internal fun piMarkdownDimens(): MarkdownDimens = markdownDimens(
    dividerThickness = 1.dp,
    codeBackgroundCornerSize = 12.dp,
    blockQuoteThickness = 3.dp,
    tableMaxWidth = Dp.Unspecified,
)
