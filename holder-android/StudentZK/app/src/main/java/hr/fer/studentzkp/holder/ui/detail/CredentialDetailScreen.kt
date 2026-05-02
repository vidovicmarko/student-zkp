package hr.fer.studentzkp.holder.ui.detail

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import java.text.SimpleDateFormat
import java.util.*

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
                        QrCodeUtils.generate(cred.bbsVcJson, 512)
                    }
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(260.dp)
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
                    DetailRow(icon = Icons.Default.CalendarToday, label = "Issued", value = formatDate(cred.issuedAt))
                }
            }

            // ── Present to verifier ─────────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Present to Verifier", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Share this BBS+ credential with a verifier. The credential proves your attributes without revealing hidden ones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Button(onClick = { vm.openPresentDialog() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Present")
                    }
                }
            }

            // ── Signed attributes ────────────────────────────────────────────
            if (state.attributeNames.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Signed Attributes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        state.attributeNames.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium)
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

    // Present-to-verifier dialog
    if (state.showPresentDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissPresentDialog() },
            icon = { Icon(Icons.Default.Send, contentDescription = null) },
            title = { Text("Present to Verifier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter a verifier nonce and audience to generate a BBS+ presentation. The credential's signature is cryptographically verified without revealing hidden attributes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = state.nonceInput,
                        onValueChange = vm::onNonceChanged,
                        label = { Text("Nonce") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.audienceInput,
                        onValueChange = vm::onAudienceChanged,
                        label = { Text("Audience (verifier URL)") },
                        placeholder = { Text("http://localhost:5173") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (state.presentationError != null) {
                        Text(state.presentationError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.presentation != null) {
                        val clipboard = LocalClipboardManager.current
                        var copiedPres by remember { mutableStateOf(false) }
                        HorizontalDivider()
                        Text("Presentation generated", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        val presBitmap: Bitmap = remember(state.presentation) {
                            QrCodeUtils.generate(state.presentation!!, 512)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = presBitmap.asImageBitmap(),
                                contentDescription = "Presentation QR",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            )
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.presentation!!))
                            copiedPres = true
                        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Icon(if (copiedPres) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (copiedPres) "Copied!" else "Copy presentation")
                        }
                    }
                }
            },
            confirmButton = {
                if (state.presentation == null) {
                    Button(onClick = { vm.generatePresentation() }) { Text("Generate") }
                } else {
                    Button(onClick = { vm.dismissPresentDialog() }) { Text("Done") }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.dismissPresentDialog() }) { Text("Close") }
            },
        )
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

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()).format(Date(epochMillis))
