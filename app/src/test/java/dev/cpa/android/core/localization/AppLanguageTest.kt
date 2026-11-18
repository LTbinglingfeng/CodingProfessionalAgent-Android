package dev.cpa.android.core.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun followsSupportedLanguageOrderAndIgnoresTraditionalChinese() {
        assertEquals(AppLanguage.English, AppLanguage.resolveSystem(listOf("zh-Hant", "en-US", "zh-CN")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.resolveSystem(listOf("zh-TW", "zh_Hans_CN", "en")))
        assertEquals(AppLanguage.English, AppLanguage.resolveSystem(listOf("zh-HK", "zh-MO", "fr")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.resolveSystem(listOf(" zh-SG ")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.resolveSystem(listOf("zh")))
        assertEquals(AppLanguage.English, AppLanguage.resolveSystem(emptyList()))
    }
}
