package dev.cpa.android.core.model

import kotlinx.serialization.json.JsonElement

/** Unknown kinds and payload fields are preserved, not interpreted as invented deltas. */
data class DesktopEvent(val kind: String, val payload: JsonElement)
