package dev.cpa.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Debug-only lifecycle host: instrumentation installs the production route before launch. */
class LocaleLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content() }
    }
    companion object {
        var content: @Composable () -> Unit = {}
    }
}
