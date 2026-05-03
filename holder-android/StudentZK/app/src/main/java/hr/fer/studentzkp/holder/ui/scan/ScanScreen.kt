package hr.fer.studentzkp.holder.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import hr.fer.studentzkp.holder.data.model.VerificationResult
import hr.fer.studentzkp.holder.domain.CredentialRepository
import hr.fer.studentzkp.holder.ui.theme.ValidGreen
import hr.fer.studentzkp.holder.ui.theme.RevokedRed
import hr.fer.studentzkp.holder.util.DateUtils
import hr.fer.studentzkp.holder.util.QrCodeUtils
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    repository: CredentialRepository,
) {
    val vm: ScanViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            ScanViewModel(repository) as T
    })
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    var pasteMode by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan & Verify") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = {
                        pasteMode = !pasteMode
                        vm.resetScan()
                    }) {
                        Icon(
                            if (pasteMode) Icons.Default.QrCodeScanner else Icons.Default.ContentPaste,
                            contentDescription = if (pasteMode) "Switch to camera" else "Paste credential",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (pasteMode) {
            PasteVerifyPanel(
                modifier = Modifier.padding(padding),
                isVerifying = state.isVerifying,
                result = state.result,
                onVerify = vm::onQrDetected,
                onReset = { vm.resetScan() },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when {
                    !hasCameraPermission -> NoCameraPermission { permLauncher.launch(Manifest.permission.CAMERA) }
                    else -> {
                        val isShowingResult = state.result != null || state.isVerifying
                        CameraPreviewWithOverlay(
                            isScanning = state.isScanning,
                            onQrDetected = vm::onQrDetected,
                        )
                        
                        // Dim background when result is shown
                        AnimatedVisibility(
                            visible = isShowingResult,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                        }

                        AnimatedVisibility(
                            visible = isShowingResult,
                            enter = scaleIn(initialScale = 0.9f) + fadeIn(),
                            exit = scaleOut(targetScale = 0.9f) + fadeOut(),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            ResultPanel(
                                isVerifying = state.isVerifying,
                                result = state.result,
                                onRescan = { vm.resetScan() },
                                onDismiss = { vm.resetScan() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteVerifyPanel(
    modifier: Modifier = Modifier,
    isVerifying: Boolean,
    result: VerificationResult?,
    onVerify: (String) -> Unit,
    onReset: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Paste a BBS+ credential JSON to verify it manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            label = { Text("BBS+ credential") },
            placeholder = { Text("{\"@context\":…}") },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = ""; onReset() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            maxLines = 8,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    if (pasted.isNotBlank()) input = pasted
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
            Button(
                onClick = { onVerify(QrCodeUtils.decompress(input.trim())) },
                enabled = input.isNotBlank() && !isVerifying,
                modifier = Modifier.weight(1f),
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isVerifying) "Verifying…" else "Verify")
            }
        }

        AnimatedVisibility(
            visible = result != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                ResultPanel(
                    isVerifying = false,
                    result = result,
                    onRescan = { onReset(); input = "" },
                    rescanLabel = "Verify Another",
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    isScanning: Boolean,
    onQrDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(executor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && isScanning) {
                                    val input = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees,
                                    )
                                    scanner.process(input)
                                        .addOnSuccessListener { barcodes ->
                                            barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                                ?.rawValue
                                                ?.let { raw -> onQrDetected(QrCodeUtils.decompress(raw)) }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Scanning frame overlay
        if (isScanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                    ) {
                        Text(
                            "Point at a StudentZK QR code",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(
    isVerifying: Boolean,
    result: VerificationResult?,
    onRescan: () -> Unit,
    rescanLabel: String = "Scan Another",
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onDismiss != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (isVerifying) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                Text("Verifying credential…", style = MaterialTheme.typography.bodyLarge)
            } else {
                when (val r = result) {
                    is VerificationResult.Valid -> ValidResultContent(r)
                    is VerificationResult.Invalid -> InvalidResultContent(r.reason)
                    is VerificationResult.Revoked -> RevokedResultContent(r.statusIdx)
                    else -> {}
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRescan,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(rescanLabel)
                }
            }
        }
    }
}

@Composable
private fun ValidResultContent(result: VerificationResult.Valid) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Determine display based on what was disclosed
        val icon: androidx.compose.ui.graphics.vector.ImageVector
        val iconColor: Color
        val title: String
        val subtitle: String

        when (result.isStudent) {
            true -> {
                icon = Icons.Default.CheckCircle
                iconColor = ValidGreen
                title = "Student Verified"
                subtitle = "This credential is valid and authentic"
            }
            false -> {
                icon = Icons.Default.Cancel
                iconColor = RevokedRed
                title = "Not a Student"
                subtitle = "Credential is valid, but holder is not marked as a student"
            }
            null -> {
                icon = Icons.Default.Verified
                iconColor = ValidGreen
                title = "Credential Valid"
                subtitle = "Signature verified — student status was not disclosed"
            }
        }

        // Large icon
        Surface(
            shape = RoundedCornerShape(50),
            color = iconColor.copy(alpha = 0.15f),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = iconColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(20.dp))

        // Detail rows — only show disclosed attributes
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var hasRow = false

                if (result.isStudent != null) {
                    DetailRow(
                        icon = Icons.Default.Badge,
                        label = "Student status",
                        value = if (result.isStudent) "Active ✓" else "Not a student",
                        valueColor = if (result.isStudent) ValidGreen else RevokedRed,
                    )
                    hasRow = true
                }
                if (!result.universityId.isNullOrBlank()) {
                    if (hasRow) HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                    DetailRow(
                        icon = Icons.Default.School,
                        label = "University",
                        value = result.universityId,
                    )
                    hasRow = true
                }
                if (result.validUntil != null) {
                    if (hasRow) HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                    DetailRow(
                        icon = Icons.Default.CalendarToday,
                        label = "Valid until",
                        value = DateUtils.formatIso(result.validUntil) ?: result.validUntil,
                    )
                    hasRow = true
                }
                if (result.ageOver18 != null) {
                    if (hasRow) HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                    DetailRow(
                        icon = Icons.Default.Person,
                        label = "Age",
                        value = if (result.ageOver18) "18+ ✓" else "Under 18",
                        valueColor = if (result.ageOver18) ValidGreen else RevokedRed,
                    )
                    hasRow = true
                }

                if (!hasRow) {
                    Text(
                        "No attributes were disclosed in this presentation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }

        if (result.statusOk == null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Status list unreachable — last-known state shown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
        )
    }
}

@Composable
private fun InvalidResultContent(reason: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = RevokedRed.copy(alpha = 0.12f),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = RevokedRed,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Verification Failed",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = RevokedRed,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = RevokedRed.copy(alpha = 0.08f),
        ) {
            Text(
                reason,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RevokedResultContent(statusIdx: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = RevokedRed.copy(alpha = 0.12f),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.GppBad,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = RevokedRed,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Credential Revoked",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = RevokedRed,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This credential has been revoked by the issuer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = RevokedRed.copy(alpha = 0.08f),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = RevokedRed.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Status index: $statusIdx",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun NoCameraPermission(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(20.dp))
        Text("Camera Permission Required", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Grant camera access to scan QR codes",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Permission") }
    }
}
