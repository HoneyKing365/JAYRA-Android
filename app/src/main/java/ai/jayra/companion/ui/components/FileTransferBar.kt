package ai.jayra.companion.ui.components

import ai.jayra.companion.model.FileTransferState
import ai.jayra.companion.model.TransferDirection
import ai.jayra.companion.model.TransferStatus
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.util.NetUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Inline transfer list with live percentage bars. */
@Composable
fun FileTransferBar(transfers: List<FileTransferState>, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    if (transfers.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        transfers.forEach { t ->
            val color = when (t.status) {
                TransferStatus.COMPLETE -> JayraEmeraldSuccess
                TransferStatus.FAILED -> JayraCrimsonDanger
                else -> JayraCyanPrimary
            }
            Column(
                Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(14.dp)).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (t.direction == TransferDirection.UPLOAD) "↑ " else "↓ ") + t.filename,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when (t.status) {
                            TransferStatus.COMPLETE -> "DONE"
                            TransferStatus.FAILED -> "FAILED"
                            else -> if (t.totalBytes > 0) "${t.percent}%" else NetUtil.formatBytes(t.transferredBytes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = color
                    )
                    if (t.status == TransferStatus.COMPLETE || t.status == TransferStatus.FAILED) {
                        IconButton(onClick = { onDismiss(t.transferId) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Dismiss", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (t.status == TransferStatus.ACTIVE || t.status == TransferStatus.PENDING) {
                    if (t.totalBytes > 0) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = color)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = color)
                }
                if (t.status == TransferStatus.FAILED && !t.detail.isNullOrBlank()) {
                    Text(t.detail, style = MaterialTheme.typography.bodySmall, color = JayraCrimsonDanger, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
