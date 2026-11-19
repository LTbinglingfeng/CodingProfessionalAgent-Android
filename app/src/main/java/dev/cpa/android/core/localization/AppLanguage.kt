package dev.cpa.android.core.localization

import java.util.Locale

enum class AppLanguage(val tag: String) {
    System(""), English("en"), SimplifiedChinese("zh-Hans");
    companion object {
        fun resolveSystem(tags: List<String>): AppLanguage {
            for (raw in tags) {
                val tag = raw.trim().lowercase(Locale.ROOT).replace('_', '-')
                if (tag == "en" || tag.startsWith("en-")) return English
                if (listOf("zh-hant", "zh-tw", "zh-hk", "zh-mo").any { tag == it || tag.startsWith("$it-") }) continue
                if (listOf("zh", "zh-hans", "zh-cn", "zh-sg").any { tag == it || (it != "zh" && tag.startsWith("$it-")) }) return SimplifiedChinese
            }
            return English
        }
    }
}
