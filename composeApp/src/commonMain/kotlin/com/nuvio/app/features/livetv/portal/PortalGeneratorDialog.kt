package com.nuvio.app.features.livetv.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio

/**
 * "Generate portals", ported from Lucid Hikari: loads Cloud Vault + Reddit, verifies Xtream
 * accounts, keeps working ones as lightweight search results, and installs one only when Add
 * is tapped. Android-only; the rows that open it are hidden where the platform is unavailable.
 */
@Composable
fun PortalGeneratorDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val saved by PortalGeneratorPlatform.savedPortals.collectAsStateWithLifecycle()
    var runKey by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Starting…") }
    var tried by remember { mutableIntStateOf(0) }
    var found by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runKey) {
        // Opening with results already saved shows them; the button starts a new run.
        if (runKey == 0 && saved.isNotEmpty()) {
            status = "${saved.size} portal${if (saved.size == 1) "" else "s"} from an earlier run."
            return@LaunchedEffect
        }
        running = true
        error = null
        tried = 0
        found = 0
        status = "Loading portals…"
        try {
            val list = PortalGeneratorPlatform.generate(target = 15) { progress ->
                status = progress.status
                tried = progress.tried
                found = progress.found
            }
            val added = PortalGeneratorPlatform.save(list)
            status = if (list.isEmpty()) {
                "No working portals found this round. Try again — the pool is shuffled."
            } else {
                "Found $added new portal${if (added == 1) "" else "s"} — tap Add on the ones you want."
            }
        } catch (e: Throwable) {
            error = e.message ?: "Couldn't generate portals."
            status = ""
        } finally {
            running = false
        }
    }

    DialogSurface(
        onDismissRequest = { if (!running) onDismiss() },
        title = "Generate Xtream portals",
        message = "Cloud Vault + Reddit · verifies live Xtream accounts. Results wait here until you Add one.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = tokens.colors.accent,
                    )
                }
                Text(
                    text = if (running) status else status.ifBlank { "Done." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (running && (tried > 0 || found > 0)) {
                Text(
                    "Tried $tried · verified $found of 15",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
            }
            if (saved.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                ) {
                    items(saved, key = { it.key }) { portal ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    portal.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        portal.host,
                                        portal.expiry,
                                        portal.maxConnections?.let { "$it conn" },
                                        portal.source,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                enabled = !portal.added,
                                onClick = {
                                    val ok = PortalGeneratorPlatform.addToLiveTv(portal)
                                    status = if (ok) {
                                        "Added ${portal.name} — updating its channels…"
                                    } else {
                                        "Couldn't add ${portal.name}."
                                    }
                                },
                            ) {
                                Text(if (portal.added) "Added" else "Add", fontSize = 13.sp)
                            }
                            TextButton(onClick = { PortalGeneratorPlatform.remove(portal) }) {
                                Text("Remove", fontSize = 12.sp, color = tokens.colors.textMuted)
                            }
                        }
                    }
                }
            }
            DialogButtons {
                DialogButton(
                    text = "Generate again",
                    enabled = !running,
                    onClick = { runKey++ },
                )
                DialogButton(
                    text = if (running) "Working…" else "Close",
                    style = DialogButtonStyle.Primary,
                    enabled = !running,
                    onClick = onDismiss,
                )
            }
        }
    }
}
