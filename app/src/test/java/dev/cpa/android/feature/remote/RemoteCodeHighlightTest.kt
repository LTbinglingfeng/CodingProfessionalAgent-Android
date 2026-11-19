package dev.cpa.android.feature.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteCodeHighlightTest {
    @Test fun codeTextAndUtf16OffsetsSurviveBothPalettes() {
        val text = """😀 let Name = "it's ok" + 1.5e2 // let 7"""
        val light = highlightSourceLine(text, "dir/file.tsx", false)
        val dark = highlightSourceLine(text, "dir/file.tsx", true)
        assertEquals(text, light.text)
        assertEquals(text, dark.text)
        fun tokens(line: androidx.compose.ui.text.AnnotatedString) =
            line.spanStyles.map { text.substring(it.start, it.end) }.toSet()
        assertEquals(setOf("let", "Name", "\"it's ok\"", "1.5e2", "// let 7"), tokens(light))
        assertEquals(tokens(light), tokens(dark))
        assertNotEquals(light.spanStyles.first().item.color, dark.spanStyles.first().item.color)
    }

    @Test fun hashCommentsUseTheFilenameAndLongQuotedLinesAreSafe() {
        val text = "a#b # example"
        for (path in listOf("Dockerfile.dev", "dir/Makefile", "tool.PY"))
            assertEquals("# example", highlightSourceLine(text, path, false).spanStyles.single().let { text.substring(it.start, it.end) })
        assertTrue(highlightSourceLine(text, "notes.txt", false).spanStyles.isEmpty())
        val long = "\"" + "a".repeat(199998) + "\""
        assertEquals(long.length, highlightSourceLine(long, "x.json", false).spanStyles.single().end)
    }
}
