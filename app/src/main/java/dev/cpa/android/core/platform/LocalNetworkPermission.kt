package dev.cpa.android.core.platform

import androidx.compose.ui.res.stringResource
import dev.cpa.android.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import dev.cpa.android.core.designsystem.Cpa
import dev.cpa.android.core.designsystem.CpaIcons
import dev.cpa.android.core.designsystem.EmptyState
import dev.cpa.android.core.designsystem.cpaText
import dev.cpa.android.core.designsystem.pressable

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
fun Context.hasLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
    checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/**
 * SDK 37 gates LAN access at runtime. iOS asks through a system prompt and has no separate page,
 * so this mirrors the Home empty state (icon, title, message, accent capsule) instead of a form.
 * Permission is checked again after returning from Settings.
 */
@Composable
fun LocalNetworkPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.hasLocalNetworkPermission()) }
    val latestOnGranted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = context.hasLocalNetworkPermission()
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasLocalNetworkPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted) { if (granted) latestOnGranted() }
    if (granted) { content(); return }
    val c = Cpa.colors
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().testTag("local-network-gate")) {
        EmptyState(CpaIcons.Display, stringResource(R.string.permission_title), stringResource(R.string.permission_explanation),
            Modifier.padding(top = 140.dp), actionTitle = stringResource(R.string.allow_lan), onAction = { launcher.launch(LOCAL_NETWORK) })
        Text(stringResource(R.string.open_settings), Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            .pressable(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }).padding(horizontal = 16.dp, vertical = 10.dp), style = cpaText(15), color = c.secondaryLabel)
    }
}
