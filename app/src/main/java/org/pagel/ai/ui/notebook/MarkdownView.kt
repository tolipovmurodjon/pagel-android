package org.pagel.ai.ui.notebook

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pagel.ai.data.notebook.Block
import org.pagel.ai.data.notebook.Inline
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons

/** Where a tap inside a note leads: a wikilink's target (resolved by the screen), a web link, a picture's bytes. */
class NoteLinks(
    val wiki: (String) -> Unit = {},
    val web: (String) -> Unit = {},
    val picture: suspend (String) -> ByteArray? = { null },
)

private val LinkStyle = TextLinkStyles(SpanStyle(color = Glass.PrimaryInk, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
/** A wikilink with no note behind it on this phone (a closed folder, or not written yet): muted, not underlined. */
private val MissingStyle = TextLinkStyles(SpanStyle(color = Glass.Muted))

/** A note's line with its marks: bold, italic, code, web links and wikilinks as tappable links. */
fun inlineText(inlines: List<Inline>, links: NoteLinks, known: (String) -> Boolean = { true }): AnnotatedString = buildAnnotatedString {
    for (i in inlines) when (i) {
        is Inline.Text -> append(i.text)
        is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(i.text) }
        is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(i.text) }
        is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Glass.Ink.copy(alpha = 0.06f))) { append(i.text) }
        is Inline.Link -> withLink(LinkAnnotation.Clickable("web", LinkStyle) { links.web(i.url) }) { append(i.text) }
        is Inline.WikiLink -> withLink(LinkAnnotation.Clickable("wiki", if (known(i.target)) LinkStyle else MissingStyle) { links.wiki(i.target) }) { append(i.shown) }
    }
}

/**
 * One block of a note, drawn: headings in Inter Tight, body in Schibsted Grotesk, ink on the glass page (≥ 4.5:1),
 * links in the mint ink (#0B5C45), task boxes as drawn rings, quotes with the mint thin line, pictures from the vault.
 */
@Composable
fun MarkdownBlock(block: Block, links: NoteLinks, known: (String) -> Boolean) {
    val type = MaterialTheme.typography
    when (block) {
        is Block.Heading -> Text(
            inlineText(block.inlines, links, known),
            style = when (block.level) { 1 -> type.headlineSmall; 2 -> type.titleLarge; else -> type.titleMedium },
            color = Glass.Ink,
            modifier = Modifier.padding(top = if (block.level <= 2) Space.md else Space.sm).semantics { heading() },
        )
        is Block.Paragraph -> Text(inlineText(block.inlines, links, known), style = type.bodyLarge, color = Glass.Ink)
        is Block.Item -> Row(Modifier.padding(start = Space.lg * block.depth), verticalAlignment = Alignment.Top) {
            when (block.checked) {
                null -> Text(block.marker, style = type.bodyLarge, color = Glass.Muted, modifier = Modifier.width(Space.xxl))
                else -> Box(Modifier.width(Space.xxl).padding(top = Space.xs)) {
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(5.dp))
                            .background(if (block.checked) Glass.Primary else Color.White.copy(alpha = 0.7f))
                            .border(1.5.dp, if (block.checked) Glass.Primary else Glass.Ink.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                            .semantics { contentDescription = if (block.checked) "Done" else "Not done" },
                        contentAlignment = Alignment.Center,
                    ) { if (block.checked) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                }
            }
            Text(
                inlineText(block.inlines, links, known), style = type.bodyLarge,
                color = if (block.checked == true) Glass.Muted else Glass.Ink,
                textDecoration = if (block.checked == true) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
        }
        is Block.Quote -> Row(Modifier.fillMaxWidth().heightIn(min = Space.xxl)) {
            Box(Modifier.width(3.dp).heightIn(min = Space.xxl).background(Glass.Primary.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(Space.md))
            Text(inlineText(block.inlines, links, known), style = type.bodyLarge.copy(fontStyle = FontStyle.Italic), color = Glass.Ink.copy(alpha = 0.86f))
        }
        is Block.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.Ink.copy(alpha = 0.05f))
                .horizontalScroll(rememberScrollState()).padding(Space.md),
        ) { Text(block.text, style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = Glass.Ink) }
        is Block.Image -> VaultPicture(block, links)
        Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.Ink.copy(alpha = 0.12f)))
        }
    }
}

/** A picture from the vault (decoded off Main), or its name as a quiet line while it cannot be shown. */
@Composable
private fun VaultPicture(block: Block.Image, links: NoteLinks) {
    val bitmap by produceState<ImageBitmap?>(null, block.target) {
        value = if (!block.wiki) null else withContext(Dispatchers.Default) {
            links.picture(block.target)?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    val pic = bitmap
    if (pic != null) {
        Image(
            pic, contentDescription = block.alt, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        )
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.5f)).padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(NotebookIcons.Image, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
            if (block.wiki) Text(block.alt, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, maxLines = 1)
            else Text(inlineText(listOf(Inline.Link(block.alt.ifBlank { "Picture" }, block.target)), links), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
