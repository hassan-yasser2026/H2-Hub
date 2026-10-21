package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichChatContentParserTest {
    @Test
    fun parsesDisplayAndInlineLatexWithoutLosingSurroundingMarkdown() {
        val content = "الحل:\n\n\$\$x^2 + y^2 = z^2\$\$ ثم \$x=2\$"

        val segments = parseRichChatContent(content)

        assertEquals(4, segments.size)
        assertTrue(segments[0] is RichChatSegment.MarkdownText)
        assertEquals(RichChatSegment.Formula("x^2 + y^2 = z^2", true), segments[1])
        assertTrue(segments[2] is RichChatSegment.MarkdownText)
        assertEquals(RichChatSegment.Formula("x=2", false), segments[3])
    }

    @Test
    fun parsesTheQuadraticExampleAsDisplayMath() {
        assertEquals(
            listOf(RichChatSegment.Formula("x^2 + 3x + 2 = 0", display = true)),
            parseRichChatContent("\$\$x^2 + 3x + 2 = 0\$\$")
        )
    }

    @Test
    fun parsesMermaidFenceAndLeavesOtherCodeAsMarkdown() {
        val content = "```mermaid\nflowchart LR\n A-->B\n```\n\n```kotlin\nval x = 1\n```"

        val segments = parseRichChatContent(content)

        assertEquals(2, segments.size)
        assertEquals(
            RichChatSegment.MermaidDiagram("flowchart LR\n A-->B"),
            segments[0]
        )
        assertTrue(segments[1] is RichChatSegment.MarkdownText)
        assertTrue((segments[1] as RichChatSegment.MarkdownText).text.contains("```kotlin"))
    }

    @Test
    fun leavesPlainMarkdownUnchangedWhenThereIsNoRichContent() {
        val content = "**إجابة** بدون رسم أو معادلة."

        assertEquals(listOf(RichChatSegment.MarkdownText(content)), parseRichChatContent(content))
    }

    @Test
    fun rendersChemicalFormulaSubscriptsInMarkdownAndLatex() {
        val segments = parseRichChatContent("H2O و CO2 و \$\$H2SO4\$\$")

        assertEquals(RichChatSegment.MarkdownText("H2O و CO2 و "), segments[0])
        assertEquals(RichChatSegment.Formula("""\mathrm{H_2SO_4}""", true), segments[1])
        assertEquals("H₂O + CO₂ + H₂SO₄", formatChemicalNotation("H2O + CO2 + H2SO4"))
    }
}
