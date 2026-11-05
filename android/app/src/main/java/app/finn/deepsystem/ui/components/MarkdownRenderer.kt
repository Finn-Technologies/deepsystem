package app.finn.deepsystem.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
    data class NumberedItem(val index: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced Code Block
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size && lines[i].trimStart().startsWith("```")) {
                i++ // consume closing ```
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        val trimmed = line.trim()
        when {
            // Headings
            trimmed.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
                i++
            }
            trimmed.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
                i++
            }
            trimmed.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
                i++
            }
            // Blockquotes
            trimmed.startsWith("> ") || trimmed == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trim().startsWith("> ") || lines[i].trim() == ">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
            }
            // Bullet List
            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                val bulletText = if (trimmed.startsWith("* ")) trimmed.removePrefix("* ").trim() else trimmed.removePrefix("- ").trim()
                blocks.add(MarkdownBlock.BulletItem(bulletText))
                i++
            }
            // Numbered List
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                val match = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
                if (match != null) {
                    val num = match.groupValues[1].toIntOrNull() ?: 1
                    val itemText = match.groupValues[2]
                    blocks.add(MarkdownBlock.NumberedItem(num, itemText))
                } else {
                    blocks.add(MarkdownBlock.Paragraph(trimmed))
                }
                i++
            }
            // Blank lines
            trimmed.isEmpty() -> {
                i++
            }
            // Regular paragraph
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].trimStart().startsWith("```") &&
                    !lines[i].trim().startsWith("#") &&
                    !lines[i].trim().startsWith(">") &&
                    !lines[i].trim().startsWith("* ") &&
                    !lines[i].trim().startsWith("- ") &&
                    !lines[i].trim().matches(Regex("^\\d+\\.\\s+.*"))
                ) {
                    paraLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
            }
        }
    }

    return blocks
}

fun buildInlineMarkdown(text: String, primaryColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            // Inline code `...`
            if (text[cursor] == '`') {
                val endIdx = text.indexOf('`', cursor + 1)
                if (endIdx != -1) {
                    val codeContent = text.substring(cursor + 1, endIdx)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            background = primaryColor.copy(alpha = 0.12f),
                            color = primaryColor
                        )
                    )
                    append(" $codeContent ")
                    pop()
                    cursor = endIdx + 1
                    continue
                }
            }

            // Bold **...**
            if (cursor + 1 < len && text[cursor] == '*' && text[cursor + 1] == '*') {
                val endIdx = text.indexOf("**", cursor + 2)
                if (endIdx != -1) {
                    val boldContent = text.substring(cursor + 2, endIdx)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldContent)
                    pop()
                    cursor = endIdx + 2
                    continue
                }
            }

            // Italic *...*
            if (text[cursor] == '*') {
                val endIdx = text.indexOf('*', cursor + 1)
                if (endIdx != -1 && endIdx > cursor + 1) {
                    val italicContent = text.substring(cursor + 1, endIdx)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(italicContent)
                    pop()
                    cursor = endIdx + 1
                    continue
                }
            }

            // Plain char
            append(text[cursor])
            cursor++
        }
    }
}

@Composable
fun MarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isSerifNotes: Boolean = false
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = buildInlineMarkdown(block.text, primaryColor),
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }

                is MarkdownBlock.CodeBlock -> {
                    CodeBlockCard(language = block.language, code = block.code)
                }

                is MarkdownBlock.Blockquote -> {
                    // Claude-style Serif Quote with left accent bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(primaryColor)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = buildInlineMarkdown(block.text, primaryColor),
                            style = SerifNoteQuote,
                            color = textColor.copy(alpha = 0.9f),
                            lineHeight = 22.sp
                        )
                    }
                }

                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = primaryColor,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = buildInlineMarkdown(block.text, primaryColor),
                            style = if (isSerifNotes) SerifNoteBody else MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            lineHeight = 22.sp
                        )
                    }
                }

                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${block.index}.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = primaryColor,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = buildInlineMarkdown(block.text, primaryColor),
                            style = if (isSerifNotes) SerifNoteBody else MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            lineHeight = 22.sp
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineMarkdown(block.text, primaryColor),
                        style = if (isSerifNotes) SerifNoteBody else MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        lineHeight = 23.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CodeBackgroundDark
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Language label & Copy Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )

                // Copy Code Button
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                        isCopied = true
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            delay(2000)
                            isCopied = false
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Copy code to clipboard"
                        }
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (isCopied) "Copied" else "Copy code",
                        tint = if (isCopied) SuccessGreen else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Code Body
            Text(
                text = code,
                style = CodeTextStyle,
                color = Color(0xFFF8FAFC),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
    }
}
