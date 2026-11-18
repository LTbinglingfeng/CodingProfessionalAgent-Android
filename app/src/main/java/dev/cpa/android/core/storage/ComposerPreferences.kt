package dev.cpa.android.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ComposerPreferences {
    val collapseKeyboardAfterSend: StateFlow<Boolean>
    suspend fun setCollapseKeyboardAfterSend(value: Boolean)
    val showContextUsage: StateFlow<Boolean>
    suspend fun setShowContextUsage(value: Boolean)
    val wrapCode: StateFlow<Boolean>
    suspend fun setWrapCode(wrap: Boolean)
}
class MemoryComposerPreferences : ComposerPreferences {
    private val collapse = MutableStateFlow(true)
    override val collapseKeyboardAfterSend = collapse.asStateFlow()
    override suspend fun setCollapseKeyboardAfterSend(value: Boolean) { collapse.value = value }
    private val contextUsage = MutableStateFlow(false)
    override val showContextUsage = contextUsage.asStateFlow()
    override suspend fun setShowContextUsage(value: Boolean) { contextUsage.value = value }
    private val mutableWrap = MutableStateFlow(true)
    override val wrapCode = mutableWrap.asStateFlow()
    override suspend fun setWrapCode(wrap: Boolean) { mutableWrap.value = wrap }
}
/** Non-secret, app-wide preference. Confirm disk persistence before publishing the new value. */
class AndroidComposerPreferences(context: Context, preferenceName: String = "composer-preferences") : ComposerPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val collapse = MutableStateFlow(preferences.getBoolean("collapseKeyboardAfterSend", true))
    override val collapseKeyboardAfterSend = collapse.asStateFlow()
    override suspend fun setCollapseKeyboardAfterSend(value: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("collapseKeyboardAfterSend", value).commit()) { "Preference write failed" }
            collapse.value = value
        }
    }
    private val contextUsage = MutableStateFlow(preferences.getBoolean("showContextUsage", false))
    override val showContextUsage = contextUsage.asStateFlow()
    override suspend fun setShowContextUsage(value: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("showContextUsage", value).commit()) { "Preference write failed" }
            contextUsage.value = value
        }
    }
    private val mutableWrap = MutableStateFlow(preferences.getBoolean("wrapCode", true))
    override val wrapCode = mutableWrap.asStateFlow()
    override suspend fun setWrapCode(wrap: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("wrapCode", wrap).commit()) { "Preference write failed" }
            mutableWrap.value = wrap
        }
    }
    private val writes = Mutex()
}
