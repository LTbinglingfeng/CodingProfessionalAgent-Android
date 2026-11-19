package dev.cpa.android.feature.remote

import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteComposerLanguageTest {
    @get:Rule(order = 0) val language = dev.cpa.android.core.localization.AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }
    @Test fun englishAttachmentsCommandsAndShareKeepPayloadUntranslated() {
        val skills = RemoteSkillsController(SavedStateHandle(), "skills", RemoteWorkspaceScreenTest.Fake(), scope, "/model")
        val attachments = RemoteAttachments(SavedStateHandle(), "attachments", scope)
        var commandCalls = 0
        compose.setContent {
            val base = LocalContext.current
            val config = LocalConfiguration.current
            val english = remember { ContextThemeWrapper(base, 0).apply {
                applyOverrideConfiguration(Configuration(config).apply { setLocales(LocaleList.forLanguageTags("en")) })
            } }
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides english.resources.configuration) {
                CpaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    var text by remember { mutableStateOf("/model") }
                    RemoteSkillComposer(skills, text, { text = it }, RemoteDefaults(), true, "Message",
                        composerCommands(null, null, { commandCalls++ }))
                    RemoteAttachmentComposer(attachments, true)
                } }
            }
        }
        compose.onNodeWithText("Choose model and reasoning effort").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message").performClick()
        compose.onNodeWithText("Choose model and reasoning effort").assertExists()
        compose.onNodeWithText("Command").assertExists()
        compose.onNodeWithText("Model").performClick()
        compose.runOnIdle { assertEquals(1, commandCalls); assertEquals("", skills.state.value.draft.text) }
        compose.onNodeWithText("Photos").assertExists()
        compose.onNodeWithText("Files").assertExists()
        compose.onNodeWithText("Camera").assertExists()
    }
}
