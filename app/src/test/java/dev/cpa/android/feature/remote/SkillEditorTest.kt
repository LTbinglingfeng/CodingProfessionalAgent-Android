package dev.cpa.android.feature.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.cpa.android.data.repository.SkillReference
import org.junit.Assert.*
import org.junit.Test

class SkillEditorTest {
    private val draft = SkillDraft("😀 \$review and \$model", listOf(SkillReference(3, "review"), SkillReference(15, "model")))
    @Test fun backwardAndForwardDeletionRemoveWholeSkillAndShiftFollowingReference() {
        val back = atomicSkillEdit(TextFieldValue(draft.text, TextRange(10)),
            TextFieldValue("😀 \$revie and \$model", TextRange(9)), draft)
        assertEquals("😀  and \$model", back.text)
        assertEquals(TextRange(3), back.selection)
        assertEquals(listOf(SkillReference(8, "model")), draft.edited(back.text).validReferences)
        val forward = atomicSkillEdit(TextFieldValue(draft.text, TextRange(3)),
            TextFieldValue("😀 review and \$model", TextRange(3)), draft)
        assertEquals(back, forward)
    }
    @Test fun cursorAndReverseSelectionCannotEnterSkill() {
        val before = TextFieldValue(draft.text, TextRange(10))
        assertEquals(TextRange(3), atomicSkillEdit(before, before.copy(selection = TextRange(9)), draft).selection)
        assertEquals(TextRange(10), atomicSkillEdit(before.copy(selection = TextRange(3)), before.copy(selection = TextRange(4)), draft).selection)
        assertEquals(TextRange(21, 3), atomicSkillEdit(before, before.copy(selection = TextRange(18, 5)), draft).selection)
    }
    @Test fun replacementExpandsTouchedSkillsButLeavesOrdinaryCompositionIntact() {
        val edited = atomicSkillEdit(TextFieldValue(draft.text, TextRange(5, 8)),
            TextFieldValue("😀 \$rXw and \$model", TextRange(6)), draft)
        assertEquals("😀 X and \$model", edited.text)
        val typing = TextFieldValue(draft.text + "ni", TextRange(23), TextRange(21, 23))
        assertEquals(typing, atomicSkillEdit(TextFieldValue(draft.text, TextRange(21)), typing, draft))
    }
    @Test fun clipboardUsesTriggerOnlyForReferencesWithinSelectedRange() {
        assertEquals("😀 /skill:review and /skill:model", draft.copySelection(TextRange(0, 21), '/'))
        assertEquals("#review", draft.copySelection(TextRange(3, 10), '#'))
        assertEquals("\$review", SkillDraft("\$review").copySelection(TextRange(0, 7), '#'))
    }
    @Test fun nativeEditorSnapshotKeepsReferencesSelectionAndComposition() {
        val value = draft.editorValue(TextRange(23), TextRange(21, 23))
        assertEquals(draft.validReferences, value.editorSkillReferences())
        val composed = draft.copy(text = draft.text + "ni").editorValue(TextRange(23), TextRange(21, 23))
        assertEquals(TextRange(21, 23), composed.composition)
        assertEquals(draft.validReferences, composed.editorSkillReferences())
        assertTrue(TextFieldValue(draft.text).editorSkillReferences().isEmpty())
    }
    @Test fun corruptOrEditedAnnotationCannotRestoreAReference() {
        val original = draft.editorValue()
        val changed = androidx.compose.ui.text.AnnotatedString.Builder(original.annotatedString).apply {
            addStringAnnotation("dev.cpa.skill-reference", "other", 3, 10)
        }.toAnnotatedString()
        assertEquals(draft.validReferences, TextFieldValue(changed).editorSkillReferences())
        val clipped = original.annotatedString.subSequence(4, 10)
        assertTrue(TextFieldValue(clipped).editorSkillReferences().isEmpty())
    }

}
