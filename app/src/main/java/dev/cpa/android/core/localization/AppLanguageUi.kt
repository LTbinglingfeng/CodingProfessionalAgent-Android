package dev.cpa.android.core.localization

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R

/** LocaleManager owns persistence and activity recreation, including changes from Android Settings. */
@Composable
fun AppLanguageResources(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val manager = context.getSystemService(LocaleManager::class.java)
    val locales = manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
    val language = AppLanguage.resolveSystem((0 until locales.size()).map { locales[it].toLanguageTag() })
    val localized = remember(context, configuration, language) {
        android.view.ContextThemeWrapper(context, 0).apply {
            applyOverrideConfiguration(Configuration(configuration).apply { setLocales(LocaleList.forLanguageTags(language.tag)) })
        }
    }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, content = content)
}

@Composable
fun AppLanguagePicker() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val manager = context.getSystemService(LocaleManager::class.java)
    val current = remember(configuration) {
        val locales = manager.applicationLocales
        if (locales.isEmpty) AppLanguage.System else AppLanguage.resolveSystem((0 until locales.size()).map { locales[it].toLanguageTag() })
    }
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.language)) }
    if (failed) Text(stringResource(R.string.language_failed), color = MaterialTheme.colorScheme.error)
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.language)) }, text = {
        Column { AppLanguage.entries.forEach { language ->
            val label = when (language) {
                AppLanguage.System -> stringResource(R.string.language_system)
                AppLanguage.English -> "English"
                AppLanguage.SimplifiedChinese -> "简体中文"
            }
            FilterChip(selected = current == language, label = { Text(label) }, onClick = {
                failed = false
                runCatching { manager.applicationLocales = LocaleList.forLanguageTags(language.tag) }
                    .onSuccess { open = false }.onFailure { failed = true }
            })
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}
