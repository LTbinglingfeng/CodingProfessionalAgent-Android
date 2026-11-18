package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RemotePromptPartsTest {
    private val skills = listOf(RemoteSkill("Review", "", ""), RemoteSkill("review", "", ""))
    private fun legacy(text: String) = RemoteEntry("u", "user", text, hasSkillReferences = false)

    @Test fun missingAndNullReferencesInferButExplicitEmptyOrMalformedDoNot() {
        for (field in listOf("", ",\"skillReferences\":null", ",\"skillReferences\":[]", ",\"skillReferences\":{}")) {
            val entry = parseRemoteEntry(Json.parseToJsonElement("""{"id":"u","kind":"user","content":[{"type":"text","text":"${'$'}review args"}]$field}""").jsonObject)!!
            assertEquals(field.isEmpty() || field.endsWith("null"), !entry.hasSkillReferences)
            assertEquals(if (entry.hasSkillReferences) "\$review args" else "/skill:Review args", entry.promptParts(skills).promptText('/'))
        }
    }
    @Test fun legacyMatchesKnownTokensCaseInsensitivelyAndPreservesSurroundingText() {
        val entry = legacy("😀 use \$review and /skill:REVIEW now")
        assertEquals(listOf(PromptPart.Text("😀 use "), PromptPart.Skill("Review"), PromptPart.Text(" and "), PromptPart.Skill("Review"), PromptPart.Text(" now")), entry.promptParts(skills))
        assertEquals("😀 use /skill:Review and /skill:Review now", entry.promptParts(skills).promptText('/'))
        assertEquals("😀 use \$review and /skill:REVIEW now", entry.text)
    }
    @Test fun boundariesUnknownInlineAndPlainSlashAreNotSkills() {
        val text = "x\$review #\$review /\$review \\${'$'}review \$review, /review \$unknown"
        assertEquals(listOf(PromptPart.Text(text)), legacy(text).promptParts(skills))
        assertEquals(listOf(PromptPart.Text("("), PromptPart.Skill("Review"), PromptPart.Text(" args)")), legacy("(\$review args)").promptParts(skills))
    }
    @Test fun wholeCommandFallbackAllowsUninstalledSkillAndTrimsArguments() {
        assertEquals(listOf(PromptPart.Skill("uninstalled"), PromptPart.Text(" args")), legacy("  /skill:uninstalled   args \n").promptParts(emptyList()))
        assertEquals(listOf(PromptPart.Skill("unknown")), legacy("\$unknown").promptParts(emptyList()))
        assertEquals(listOf(PromptPart.Text("\$")), legacy("\$").promptParts(emptyList()))
    }
    @Test fun explicitReferencesUseUtf16AndNeverInferOtherTokens() {
        val entry = RemoteEntry("u", "user", "😀 \$Review \$unknown", skillReferences = listOf(SkillReference(3, "Review")))
        assertEquals("😀 /skill:Review \$unknown", entry.promptParts(emptyList()).promptText('/'))
        assertEquals(listOf(PromptPart.Text("\$Review")), RemoteEntry("u", "user", "\$Review").promptParts(skills))
    }
}
