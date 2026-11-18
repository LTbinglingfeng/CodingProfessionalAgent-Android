package dev.cpa.android.core.localization

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.cpa.android.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppLanguageDeviceTest {
    @get:Rule(order = 0) val permission = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
                base.evaluate()
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun switchesPersistsAcrossRecreationAndReturnsToSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocaleManager::class.java)
        val original = manager.applicationLocales
        try {
            fun choose(option: String) {
                compose.onNodeWithTag("home-settings").performClick()
                compose.onNodeWithTag("settings-language").performClick()
                compose.onNodeWithText(option).performClick()
            }
            choose("English")
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("en", manager.applicationLocales.toLanguageTags())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Search Chats").assertExists()
            choose("简体中文")
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty() }
            choose("跟随系统")
            compose.waitUntil(10000) { manager.applicationLocales.isEmpty }
            val system = AppLanguage.resolveSystem((0 until manager.systemLocales.size()).map { manager.systemLocales[it].toLanguageTag() })
            val label = if (system == AppLanguage.English) "Settings" else "设置"
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.applicationLocales = original }
        }
    }
}
