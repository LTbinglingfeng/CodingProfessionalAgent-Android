package dev.cpa.android

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner

/** UI assertions use a declared Chinese fixture locale; restore the prior setting after the run. */
class LocalizedTestRunner : AndroidJUnitRunner() {
    private var original: LocaleList? = null
    override fun onStart() {
        val manager = targetContext.getSystemService(LocaleManager::class.java)
        original = manager.applicationLocales
        runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags("zh-Hans") }
        super.onStart()
    }
    override fun finish(resultCode: Int, results: Bundle?) {
        original?.let { locales ->
            runOnMainSync { targetContext.getSystemService(LocaleManager::class.java).applicationLocales = locales }
        }
        super.finish(resultCode, results)
    }
}
