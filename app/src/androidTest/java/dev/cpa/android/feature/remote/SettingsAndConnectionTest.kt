package dev.cpa.android.feature.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryPhase
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryState
import dev.cpa.android.core.network.tunnel.TunnelProtocol
import dev.cpa.android.core.storage.ConnectionMode
import dev.cpa.android.core.storage.RecentTunnelSummary
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsAndConnectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsTogglesDisconnectAllPreferencesAndConfirmedDelete() {
        val repo = UiFakeRepository().apply {
            connections.value = listOf(ConnectionSummary("a", "A", "http://a:1", true, ConnectionPhase.Connected), ConnectionSummary("b", "B", "http://b:1", true))
        }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val connections by repo.connections.collectAsState()
            SettingsSheet(vm, connections, emptyMap(), onDismiss = {}, onAdd = {}, onEdit = {}) } }
        compose.onNodeWithTag(homeKey("connection-toggle", "a")).assertIsOn().performClick()
        compose.waitUntil(5_000) { "disconnect:a" in repo.calls }
        compose.onNodeWithTag("settings-disconnect-all").performClick()
        compose.waitUntil(5_000) { "disconnectAll" in repo.calls }
        compose.onNodeWithText("已连接").assertExists()
        compose.onNodeWithTag("settings-edit").performClick()
        compose.onNodeWithTag(homeKey("delete-connection", "b")).performClick()
        compose.onNodeWithText("删除“B”？").assertExists()
        compose.runOnIdle { assertFalse(repo.calls.contains("remove:b")) }
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(5_000) { "remove:b" in repo.calls }
        compose.onNodeWithText("跟进处理方式").assertTextContains("跟随桌面端设置").assertHasNoClickAction()
        compose.onNodeWithText("显示上下文窗口使用情况").performClick()
        compose.waitUntil(5_000) { repo.showContextUsage.value }
    }

    @Test fun englishSettingsDescribeDesktopHandlingWithoutAnAction() {
        compose.setContent { Localized("en") { FollowUpSettingRow() } }
        compose.onNodeWithText("Follow-up handling").assertTextContains("Use Desktop Setting").assertHasNoClickAction()
    }

    @Test fun reorderHandleDispatchesNewOrder() {
        val repo = UiFakeRepository().apply {
            connections.value = listOf(ConnectionSummary("a", "A", "http://a:1", true), ConnectionSummary("b", "B", "http://b:1", true))
        }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val connections by repo.connections.collectAsState()
            SettingsSheet(vm, connections, emptyMap(), onDismiss = {}, onAdd = {}, onEdit = {}) } }
        compose.onNodeWithTag("settings-edit").performClick()
        compose.onAllNodesWithContentDescription("reorder").onFirst().performTouchInput { swipeDown(startY = centerY, endY = centerY + 220f, durationMillis = 600) }
        compose.waitUntil(5_000) { repo.calls.any { it.startsWith("reorder:") } }
        compose.runOnIdle { assertTrue(repo.calls.contains("reorder:b,a")) }
    }

    @Test fun directEditorRequiresHttpConsentAndSavesOrigin() {
        val repo = UiFakeRepository().apply { connections.value = emptyList() }
        val vm = homeViewModel(repo)
        var closed = false
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = false), emptyList(), emptyMap(), op, onDismiss = { closed = true }) } }
        compose.onNodeWithText("添加桌面端").assertExists()
        compose.onNodeWithTag("connection-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("显示名称").performTextInput("My Mac")
        compose.onNodeWithContentDescription("主机").performTextInput("192.168.1.9")
        compose.onNodeWithContentDescription("端口").performTextReplacement("18081")
        compose.onNodeWithContentDescription("密码").performTextInput("pw")
        compose.onNodeWithText("连接未加密，凭据和内容可能被截获。仅用于可信网络。").assertExists()
        compose.onNodeWithTag("connection-save").assertIsNotEnabled()
        compose.onNodeWithTag("connection-cleartext").performClick()
        compose.onNodeWithTag("connection-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { closed }
        compose.runOnIdle { assertEquals(listOf(null, "My Mac", "http://192.168.1.9:18081", "pw", true), repo.saved) }
    }

    @Test fun pastedHttpsAddressNeedsNoConsentAndInvalidHostIsReported() {
        val repo = UiFakeRepository().apply { connections.value = emptyList() }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = false), emptyList(), emptyMap(), op, onDismiss = {}) } }
        compose.onNodeWithContentDescription("主机").performTextInput("ftp://desk")
        compose.onNodeWithText("请输入 https:// 或明确确认风险的 http:// 地址。").assertExists()
        compose.onNodeWithContentDescription("主机").performTextReplacement("https://desk.example:8443/")
        compose.onNodeWithText("连接未加密，凭据和内容可能被截获。仅用于可信网络。").assertDoesNotExist()
        compose.onNodeWithTag("connection-save").performClick()
        compose.waitUntil(5_000) { repo.saved != null }
        compose.runOnIdle { assertEquals("https://desk.example:8443", repo.saved!![2]); assertEquals(false, repo.saved!![4]) }
    }

    @Test fun editingKeepsSecretsUnlessChangedAndDeleteNeedsConfirmation() {
        val existing = ConnectionSummary("id", "PC", "http://10.0.0.2:18080/", true, ConnectionPhase.Connected)
        val repo = UiFakeRepository().apply { connections.value = listOf(existing) }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(existing), listOf(existing), emptyMap(), op, onDismiss = {}) } }
        compose.onNodeWithText("编辑桌面端").assertExists()
        compose.onNodeWithContentDescription("主机").assertTextEquals("10.0.0.2")
        compose.onNodeWithContentDescription("端口").assertTextEquals("18080")
        compose.onNodeWithTag("connection-save").performClick()
        compose.waitUntil(5_000) { repo.saved != null }
        compose.runOnIdle { assertEquals(listOf("id", "PC", "http://10.0.0.2:18080", null, true), repo.saved) }
        compose.onNodeWithText("删除连接").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(repo.calls.contains("remove:id")) }
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(5_000) { "remove:id" in repo.calls }
    }

    @Test fun restorationNeverRestoresPasswords() {
        val repo = UiFakeRepository().apply { connections.value = emptyList() }
        val vm = homeViewModel(repo)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = false), emptyList(), emptyMap(), op, onDismiss = {}) } }
        compose.onNodeWithContentDescription("密码").performTextInput("private-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("密码").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun tunnelDiscoveryListsServersMarksAddedAndSavesDetail() {
        val updates = MutableStateFlow(TunnelDiscoveryState(TunnelDiscoveryPhase.Connected))
        val existing = ConnectionSummary("existing", "PC", "https://gateway.test", false, mode = ConnectionMode.Tunnel, tunnelServerId = "pc")
        val repo = UiFakeRepository().apply { connections.value = listOf(existing); discovery = updates }
        val vm = homeViewModel(repo)
        var saved = false
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = true), listOf(existing), emptyMap(), op, onDismiss = {}, onSaved = { saved = true }) } }
        compose.onNodeWithTag("tunnel-search").assertIsNotEnabled()
        compose.onNodeWithContentDescription("URL 地址").performTextInput("https://gateway.test")
        compose.onNodeWithContentDescription("API Key").performTextInput("secret-key")
        compose.onNodeWithTag("tunnel-search").performClick()
        compose.onNodeWithText("当前 API Key 下暂无在线的远程服务器。请确认电脑端已开启 CLIProxyAPI Tunnel。", substring = true).assertExists()
        compose.runOnIdle { updates.value = TunnelDiscoveryState(TunnelDiscoveryPhase.Connected, listOf(
            TunnelProtocol.Server("pc", "Old PC", TunnelProtocol.SERVER_TYPE), TunnelProtocol.Server("desk", "Remote", TunnelProtocol.SERVER_TYPE))) }
        compose.onNodeWithText("已添加").assertExists()
        compose.onNodeWithTag("tunnel-server:desk").performClick()
        compose.onNodeWithText("添加远程服务器").assertExists()
        compose.onNodeWithTag("tunnel-save").performClick()
        compose.waitUntil(5_000) { saved }
        compose.runOnIdle {
            assertTrue(repo.calls.contains("watch:https://gateway.test:secret-key"))
            assertEquals(listOf(null, "Remote", "https://gateway.test", "secret-key", "desk", "Remote", null, false), repo.tunnelDraft)
        }
    }

    @Test fun tunnelStatusShowsReconnectAndRecentGatewayWithoutKey() {
        val updates = MutableStateFlow(TunnelDiscoveryState(TunnelDiscoveryPhase.Reconnecting, reconnectAttempt = 3))
        val repo = UiFakeRepository().apply { connections.value = emptyList(); discovery = updates; recent = RecentTunnelSummary("https://gateway.test", true) }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = true), emptyList(), emptyMap(), op, onDismiss = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("将使用此网关已加密保存的最近密钥。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("URL 地址").assertTextEquals("https://gateway.test")
        compose.onNodeWithContentDescription("API Key").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("tunnel-search").assertIsEnabled().performClick()
        compose.onNodeWithText("连接中断，正在第 3 次重连…").assertExists()
        compose.onNodeWithContentDescription("URL 地址").performTextReplacement("https://other.test")
        compose.onNodeWithTag("tunnel-search").assertIsNotEnabled()
        compose.onNodeWithText("网关地址或 API Key 已变更", substring = true).assertExists()
    }

    @Test fun activeDiscoveryStopsInBackgroundAndResumesOnForeground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        var starts = 0; var active = 0
        val repo = UiFakeRepository().apply { connections.value = emptyList(); discovery = flow {
            starts++; active++
            try { emit(TunnelDiscoveryState(TunnelDiscoveryPhase.Connected)); awaitCancellation() } finally { active-- }
        } }
        val vm = homeViewModel(repo)
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = true), emptyList(), emptyMap(), op, onDismiss = {}) } } }
        compose.onNodeWithContentDescription("URL 地址").performTextInput("http://gateway.test")
        compose.onNodeWithContentDescription("API Key").performTextInput("k")
        compose.onNodeWithTag("tunnel-cleartext").performClick()
        compose.onNodeWithTag("tunnel-search").performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, active); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(0, active); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2, starts); assertEquals(1, active) }
        compose.onNodeWithTag("tunnel-cleartext").performClick()
        compose.onNodeWithTag("tunnel-search").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, active); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2, starts); assertEquals(0, active) }
    }

    @Test fun editingTunnelKeepsTargetAndOmittedCredentials() {
        val saved = ConnectionSummary("id", "Existing", "https://gateway.test", false, mode = ConnectionMode.Tunnel, tunnelServerId = "desk", tunnelServerName = "Remote")
        val repo = UiFakeRepository().apply { connections.value = listOf(saved) }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(saved), listOf(saved), emptyMap(), op, onDismiss = {}) } }
        compose.onNodeWithContentDescription("CLIProxyAPI").assertTextEquals("https://gateway.test")
        compose.onNodeWithText("Remote").assertExists()
        compose.onNodeWithTag("connection-save").performClick()
        compose.waitUntil(5_000) { repo.tunnelDraft != null }
        compose.runOnIdle { assertEquals(listOf("id", "Existing", "https://gateway.test", null, "desk", "Remote", null, false), repo.tunnelDraft) }
    }
}
