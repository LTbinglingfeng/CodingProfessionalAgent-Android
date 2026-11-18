package dev.cpa.android.feature.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R

/** Match iOS ReasoningChoice: known IDs are localized; unknown remote labels stay intact. */
@Composable
internal fun reasoningLabel(id: String, fallback: String = id): String = when (id.lowercase()) {
    "off" -> stringResource(R.string.reasoning_off)
    "minimal" -> stringResource(R.string.reasoning_minimal)
    "low" -> stringResource(R.string.reasoning_low)
    "medium" -> stringResource(R.string.reasoning_medium)
    "high" -> stringResource(R.string.reasoning_high)
    "xhigh" -> stringResource(R.string.reasoning_xhigh)
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> fallback
}

@Composable
internal fun speedLabel(id: String): String = when (id) {
    "standard" -> stringResource(R.string.speed_standard)
    "fast" -> stringResource(R.string.speed_fast)
    else -> id
}
