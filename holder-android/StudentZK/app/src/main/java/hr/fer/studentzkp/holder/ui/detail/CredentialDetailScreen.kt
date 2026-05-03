package hr.fer.studentzkp.holder.ui.detail

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import hr.fer.studentzkp.holder.domain.CredentialRepository
import hr.fer.studentzkp.holder.ui.theme.CardGradientEnd
import hr.fer.studentzkp.holder.ui.theme.CardGradientStart
import hr.fer.studentzkp.holder.util.DateUtils
import hr.fer.studentzkp.holder.util.QrCodeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    credentialId: String,
    repository: CredentialRepository,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm: CredentialDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            CredentialDetailViewModel(credentialId, repository) as T
    })
    val state by vm.uiState.collectAsState()
    val cred = state.credential

    if (cred == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credential Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.confirmDelete() }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Credential card banner ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(CardGradientStart, CardGradientEnd)))
                    .padding(20.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column {
                            Text(
                                cred.credentialType,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                            Text(
                                "Student Credential",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }
                        Icon(
                            Icons.Default.School,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White.copy(alpha = 0.9f),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        cred.studentId,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Valid until ${DateUtils.formatIso(cred.validUntil) ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }

            // ── QR Code ─────────────────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                val clipboard = LocalClipboardManager.current
                var copied by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val qrBitmap: Bitmap = remember(cred.bbsVcJson) {
                        QrCodeUtils.generate(cred.bbsVcJson)
                    }
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(300.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(cred.bbsVcJson))
                        copied = true
                    }) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy credential",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (copied) "Copied!" else "Copy credential")
                    }
                }
            }

            // ── Credential info ─────────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    DetailRow(icon = Icons.Default.VerifiedUser, label = "Student Status", value = if (cred.isStudent) "✓ Verified Student" else "Not a Student")
                    if (!cred.universityId.isNullOrBlank()) {
                        DetailRow(icon = Icons.Default.AccountBalance, label = "University ID", value = cred.universityId)
                    }
                    DetailRow(icon = Icons.Default.Fingerprint, label = "Credential ID", value = cred.id.take(16) + "…")
                    DetailRow(icon = Icons.Default.CalendarToday, label = "Issued", value = DateUtils.formatEpochWithTime(cred.issuedAt))
                }
            }

            // ── Selective Disclosure ──────────────────────────────────────
            if (state.indexedAttributes.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Selective Disclosure", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Choose which attributes to reveal. Unselected attributes stay hidden but the proof remains cryptographically valid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(4.dp))
                        state.indexedAttributes.forEach { (idx, name) ->
                            val checked = idx in state.selectedIndices
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.toggleAttribute(idx) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { vm.toggleAttribute(idx) },
                                )
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        if (state.presentationError != null) {
                            Text(
                                state.presentationError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (state.presentationJson == null) {
                            Button(
                                onClick = { vm.generatePresentation() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.selectedIndices.isNotEmpty() && !state.isGenerating,
                            ) {
                                if (state.isGenerating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Generating…")
                                } else {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Generate Proof (${state.selectedIndices.size}/${state.indexedAttributes.size} attributes)")
                                }
                            }
                        } else {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                "✓ Proof generated — only ${state.selectedIndices.size} of ${state.indexedAttributes.size} attributes revealed",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val presBitmap: Bitmap = remember(state.presentationJson) {
                                QrCodeUtils.generate(state.presentationJson!!)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = presBitmap.asImageBitmap(),
                                    contentDescription = "Selective disclosure QR",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(300.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                                )
                            }
                            val clipboard = LocalClipboardManager.current
                            var copiedPres by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.presentationJson!!))
                                    copiedPres = true
                                }) {
                                    Icon(
                                        if (copiedPres) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (copiedPres) "Copied!" else "Copy proof")
                                }
                                TextButton(onClick = { vm.clearPresentation() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("New proof")
                                }
                            }
                        }
                    }
                }
            }

            // ── Raw BBS+ Credential section ──────────────────────────────────────────────
            var showRaw by remember { mutableStateOf(false) }
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Raw BBS+ Credential", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showRaw = !showRaw }) {
                            Text(if (showRaw) "Hide" else "Show")
                        }
                    }
                    AnimatedVisibility(visible = showRaw, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        SelectionContainer {
                            Text(
                                cred.bbsVcJson,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    // Delete confirmation dialog
    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissDelete() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Credential?") },
            text = { Text("This will permanently remove the credential from your wallet. You cannot undo this action.") },
            confirmButton = {
                Button(
                    onClick = { vm.doDelete(onDeleted) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.dismissDelete() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// Needed for selectable text
@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}
