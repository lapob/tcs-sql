package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.topcash.studio.data.AppViewModel

/** Shown when the administrator published a newer APK on the office server: one tap downloads it and opens the installer. */
@Composable fun UpdateCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val b by vm.update.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.checkUpdate() }
    AnimatedVisibility(b != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val u = b ?: return@AnimatedVisibility
        CardBox(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nuova versione ${u.version}", style = MaterialTheme.typography.titleSmall)
                    Text(u.notes.ifBlank { "Pubblicata dall'amministratore · ora hai la ${vm.appVersion}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Button({ vm.installUpdate(ctx) }, enabled = progress == null) { Text(if (progress == null) "Aggiorna" else "${((progress ?: 0f) * 100).toInt()}%") }
            }
            progress?.let { p -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 10.dp)) }
        }
    }
}
