package dev.cpa.android.feature.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerReturnTest {
    @Test fun standaloneReturnMayReplaceSelection() {
        assertTrue(isPlainReturnEdit(TextFieldValue("abc", TextRange(1, 2)), TextFieldValue("a\nc", TextRange(2))))
    }
    @Test fun compositionAndMultilinePasteAreNeverReturnActions() {
        val before = TextFieldValue("ni", TextRange(2), TextRange(0, 2))
        assertFalse(isPlainReturnEdit(before, TextFieldValue("ni\n", TextRange(3))))
        assertFalse(isPlainReturnEdit(before.copy(composition = null), TextFieldValue("ni\n", TextRange(3), TextRange(0, 2))))
        assertFalse(isPlainReturnEdit(TextFieldValue("a", TextRange(1)), TextFieldValue("a\nb", TextRange(3))))
    }
}
