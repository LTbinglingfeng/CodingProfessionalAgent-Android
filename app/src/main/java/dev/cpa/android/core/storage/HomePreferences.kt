package dev.cpa.android.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Connection glyphs offered by the editor, in iOS order. */
enum class ConnectionIcon(val key: String) {
    Code("code"), Terminal("terminal"), Laptop("laptop"), Display("display");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Display }
}

interface HomePreferences {
    val collapsedGroups: StateFlow<Set<String>>
    suspend fun toggleGroup(key: String)
    /** Non-secret, device-local glyph per connection ID (iOS stores it beside the connection). */
    val connectionIcons: StateFlow<Map<String, String>> get() = NoIcons
    suspend fun setConnectionIcon(connectionId: String, icon: ConnectionIcon) {}
}
private val NoIcons = MutableStateFlow<Map<String, String>>(emptyMap())
class MemoryHomePreferences : HomePreferences {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    private val icons = MutableStateFlow<Map<String, String>>(emptyMap())
    override val collapsedGroups = state.asStateFlow()
    override val connectionIcons = icons.asStateFlow()
    override suspend fun toggleGroup(key: String) {
        state.value = if (key in state.value) state.value - key else state.value + key
    }
    override suspend fun setConnectionIcon(connectionId: String, icon: ConnectionIcon) { icons.value = icons.value + (connectionId to icon.key) }
}
/** Non-secret UI preference; publish only after the write succeeds. */
class AndroidHomePreferences(context: Context, name: String = "home-preferences") : HomePreferences {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getStringSet("collapsedGroups", emptySet()).orEmpty().toSet())
    private val icons = MutableStateFlow(preferences.getStringSet("connectionIcons", emptySet()).orEmpty()
        .mapNotNull { entry -> entry.split('\n', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap())
    private val writes = Mutex()
    override val collapsedGroups = state.asStateFlow()
    override val connectionIcons = icons.asStateFlow()
    override suspend fun toggleGroup(key: String) = writes.withLock {
        val next = if (key in state.value) state.value - key else state.value + key
        withContext(Dispatchers.IO) {
            check(preferences.edit().putStringSet("collapsedGroups", next).commit()) { "Preference write failed" }
            state.value = next
        }
    }
    override suspend fun setConnectionIcon(connectionId: String, icon: ConnectionIcon) = writes.withLock {
        val next = icons.value + (connectionId to icon.key)
        withContext(Dispatchers.IO) {
            check(preferences.edit().putStringSet("connectionIcons", next.map { "${it.key}\n${it.value}" }.toSet()).commit()) { "Preference write failed" }
            icons.value = next
        }
    }
}
