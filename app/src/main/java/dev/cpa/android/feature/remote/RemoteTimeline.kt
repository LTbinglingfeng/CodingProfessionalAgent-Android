package dev.cpa.android.feature.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.RemoteEntry
import kotlinx.coroutines.launch

/** Local viewport intent, never a reason to send/replay a remote operation. */
@Composable
internal fun RemoteTimeline(entries: List<RemoteEntry>, loading: Boolean, modifier: Modifier = Modifier, onEditMessage: ((RemoteEntry) -> Unit)? = null,
    wrapCode: Boolean = true, running: Boolean = false, runningTools: Map<String, dev.cpa.android.data.repository.RemoteRunningTool> = emptyMap(),
    subAgents: List<dev.cpa.android.data.repository.RemoteSubAgent> = emptyList(), onSubAgent: ((dev.cpa.android.data.repository.RemoteSubAgent) -> Unit)? = null,
    onForkMessage: ((RemoteEntry) -> Unit)? = null, skillTrigger: Char = '$', knownSkills: List<dev.cpa.android.data.repository.RemoteSkill> = emptyList(),
    startsFollowing: Boolean = true, isPaused: Boolean = false, followRequests: Int = 0, editingEntryId: String? = null,
    inlineEditor: (@Composable (RemoteEntry) -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 12.dp), emptyText: Boolean = true,
    isBuildingSystemPrompt: Boolean = false) {
    val turns = remember(entries, running) { remoteTurns(entries, running) }
    // Keep a populated-list anchor independently: LazyListState's public position/Saver can
    // become zero while a recreated repository is still waiting for its remote snapshot.
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }
    val list = rememberLazyListState(savedIndex, savedOffset)
    var following by rememberSaveable { mutableStateOf(startsFollowing) }
    val scope = rememberCoroutineScope()
    val paused by rememberUpdatedState(isPaused)
    val userCount = entries.count { it.kind == "user" }
    var previousUserCount by remember { mutableIntStateOf(userCount) }
    var hasContentBaseline by remember { mutableStateOf(entries.isNotEmpty()) }
    var previousRequest by remember { mutableIntStateOf(followRequests) }
    LaunchedEffect(userCount, entries.isEmpty(), followRequests, isPaused) {
        val requested = (hasContentBaseline && userCount > previousUserCount) || followRequests != previousRequest
        if (entries.isNotEmpty()) hasContentBaseline = true
        previousUserCount = userCount
        previousRequest = followRequests
        if (isPaused) following = false
        else if (requested && !list.isScrollInProgress) following = true
    }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val tailGap = with(LocalDensity.current) { 25.dp.toPx() }
    val gestures = remember(list, threshold, tailGap) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Relinquish immediately, before a pending stream/layout effect can steal a drag.
                if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y < 0f) {
                    following = list.nearTimelineEnd(threshold, tailGap)
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                following = list.nearTimelineEnd(threshold, tailGap)
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(list) {
        var externalScroll = false
        var awaitingContent = true
        // Remeasure, not entry count: full-entry wire replacements can grow a single huge item.
        // Also handles viewport shrink from IME, rotation and composer/status height changes.
        snapshotFlow { Triple(list.layoutInfo, list.isScrollInProgress, following) to paused }.collect { (viewport, pause) ->
            val (layout, scrolling, follow) = viewport
            if (layout.totalItemsCount == 0) {
                awaitingContent = true
                return@collect
            }
            if (awaitingContent) {
                awaitingContent = false
                if (!follow) {
                    list.scrollToItem(savedIndex.coerceAtMost(layout.totalItemsCount - 1), savedOffset)
                    return@collect
                }
            }
            if (pause) {
                following = false
            } else if (scrolling) {
                // Includes accessibility/semantics scrolling, which bypasses nested gestures.
                // Our own scrollToItem is awaited by this collector, so it cannot enter here.
                externalScroll = true
                following = false
            } else if (externalScroll) {
                externalScroll = false
                following = list.nearTimelineEnd(threshold, tailGap)
            } else if (follow && layout.totalItemsCount > 0 && list.canScrollForward) {
                // A tiny end anchor reaches the real bottom even when the last message is taller
                // than the viewport. Instant scrolling avoids per-token animation restarts.
                list.scrollToItem(layout.totalItemsCount - 1)
            } else if (!follow && list.nearTimelineEnd(threshold, tailGap)) {
                following = true
            }
            if (!scrolling) {
                savedIndex = list.firstVisibleItemIndex
                savedOffset = list.firstVisibleItemScrollOffset
            }
        }
    }
    val showLatest by remember { derivedStateOf { !following && list.canScrollForward } }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("remote-timeline").nestedScroll(gestures), state = list,
            verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = contentPadding) {
            items(turns, key = { it.id }) { turn ->
                when (turn) {
                    is RemoteTurn.Assistant -> RemoteAssistantTurn(turn, runningTools, wrapCode, subAgents, onSubAgent, onForkMessage, isBuildingSystemPrompt)
                    is RemoteTurn.Entry -> {
                        val entry = turn.entry
                        when {
                            entry.kind == "compaction" -> CompactionDivider()
                            entry.kind == "user" && entry.id == editingEntryId && inlineEditor != null -> inlineEditor(entry)
                            entry.kind == "user" -> UserBubble(entry, knownSkills, skillTrigger,
                                onEdit = if (onEditMessage != null && entry.pendingStatus == null) ({ onEditMessage(entry) }) else null)
                            else -> RemoteMessage(entry, wrapCode, knownSkills)
                        }
                    }
                }
            }
            if (isBuildingSystemPrompt && turns.none { it is RemoteTurn.Assistant && it.live }) {
                item(key = "system-prompt-build-status") { Box(Modifier.animateItem()) { SystemPromptBuildStatus() } }
            }
            footer?.let { content -> item(key = "footer") { content() } }
            // Do not confuse placeholder geometry with actual remote content while restoring.
            if (entries.isNotEmpty()) item(key = "tail") {
                Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("remote-timeline-end"))
            }
        }
        if (emptyText && entries.isEmpty() && !loading && !isBuildingSystemPrompt) Text(stringResource(R.string.no_entries), Modifier.align(Alignment.Center).padding(16.dp),
            style = cpaText(13), color = Cpa.colors.secondaryLabel)
        AnimatedVisibility(entries.isNotEmpty() && !isPaused && showLatest, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = scaleIn(initialScale = .6f) + fadeIn(), exit = scaleOut(targetScale = .6f) + fadeOut()) {
            GlassIconButton(CpaIcons.ArrowDown, stringResource(R.string.ui_thread_scroll_to_bottom), {
                following = true
                scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            }, iconSize = 20.dp, modifier = Modifier.testTag("jump-latest"))
        }
    }
}

private fun LazyListState.nearTimelineEnd(threshold: Float, tailGap: Float): Boolean {
    if (!canScrollForward) return true
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return false
    val remainingItems = layout.totalItemsCount - 1 - last.index
    if (remainingItems > 1) return false
    val remaining = last.offset.toFloat() + last.size + layout.afterContentPadding - layout.viewportEndOffset +
        if (remainingItems == 1) tailGap else 0f
    return remaining <= threshold
}
