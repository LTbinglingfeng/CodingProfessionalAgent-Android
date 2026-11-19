package dev.cpa.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R

/** Platform dialog supplies back handling/focus; the surface is CPA rather than Material AlertDialog. */
@Composable
fun CpaDialog(title: String, message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { CpaWindowContent {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .clip(CpaTokens.ComposerShape).background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(24.dp)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            CpaAction(stringResource(R.string.dialog_acknowledge), onDismiss, Modifier.fillMaxWidth(), filled = true)
        }
    } }
}
