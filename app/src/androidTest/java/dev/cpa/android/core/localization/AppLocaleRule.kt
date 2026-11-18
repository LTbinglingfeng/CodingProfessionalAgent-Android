package dev.cpa.android.core.localization

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Apply before the Compose Activity rule so Dialogs inherit the tested application language. */
class AppLocaleRule(private val tag: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
            val original = manager.applicationLocales
            instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags(tag) }
            try { base.evaluate() } finally { instrumentation.runOnMainSync { manager.applicationLocales = original } }
        }
    }
}
