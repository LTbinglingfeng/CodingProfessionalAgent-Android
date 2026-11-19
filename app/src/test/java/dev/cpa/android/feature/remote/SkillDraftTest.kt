package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class SkillDraftTest {
    private val skills = listOf("review", "model", "fast").map { RemoteSkill(it, "", "/$it/SKILL.md") }
    @Test fun normalizationUsesUtf16OffsetsAndTrimsWithoutBreakingReferences() {
        val payload = SkillDraft("  😀 #REVIEW 继续 #unknown\n").payload(skills, '#')
        assertEquals("😀 \$review 继续 #unknown", payload.text)
        assertEquals(listOf(SkillReference(3, "review")), payload.references)
        assertEquals("😀 /skill:review 继续 #unknown", payload.plainText('/'))
    }
    @Test fun slashCommandsStayTextButExplicitSkillAndNonleadingTokensNormalize() {
        assertEquals("/model", SkillDraft("/model").payload(skills, '/').text)
        assertEquals(emptyList<SkillReference>(), SkillDraft("/model").payload(skills, '/').references)
        assertEquals("\$model", SkillDraft("/skill:model").payload(skills, '/').text)
        assertEquals("use \$model", SkillDraft("use /model").payload(skills, '/').text)
        assertEquals("\$fast", SkillDraft("/fast").payload(skills, '/').text)
        assertEquals("models", SkillDraft("  /models").slashQuery)
        assertNull(SkillDraft("/model ").slashQuery)
    }
    @Test fun tokensRequireBoundariesAndWhitespaceTerminators() {
        val draft = SkillDraft("a#review ##review /#review #review, (#review \n#review")
        assertEquals(2, draft.payload(skills, '#').references.size)
        assertEquals(draft.text.substringBefore("(#review"), draft.payload(skills, '#').text.substringBefore("(\$review"))
    }
    @Test fun insertionAtCaretPreservesFollowingTextAndReferences() {
        val draft = SkillDraft("😀 #re then \$model", listOf(SkillReference(12, "model")), 6)
        assertEquals("re", draft.skillQuery('#'))
        val inserted = draft.insertingSkill("review", '#')!!
        assertEquals("😀 \$review  then \$model", inserted.text)
        assertEquals(listOf(SkillReference(3, "review"), SkillReference(17, "model")), inserted.references)
        assertEquals(11, inserted.cursor)
        assertNull(inserted.copy(cursor = 8).skillQuery('$'))
    }
    @Test fun editsShiftUntouchedReferencesAndDiscardChangedChips() {
        val original = SkillDraft("\$review and \$model", listOf(SkillReference(0, "review"), SkillReference(12, "model")))
        val shifted = original.edited("😀 \$review and \$model")
        assertEquals(listOf(3, 15), shifted.validReferences.map { it.start })
        assertEquals(listOf(SkillReference(11, "model")), original.edited("\$revie and \$model").validReferences)
        val invalid = SkillDraft("\$review", listOf(SkillReference(Int.MAX_VALUE, "review"), SkillReference(-1, "review"), SkillReference(0, "review"), SkillReference(0, "review")))
        assertEquals(listOf(SkillReference(0, "review")), invalid.validReferences)
    }
}
