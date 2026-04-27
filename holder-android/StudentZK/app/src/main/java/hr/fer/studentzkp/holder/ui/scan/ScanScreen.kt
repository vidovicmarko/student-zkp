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
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    repository: CredentialRepository,
    onNavigateToResult: () -> Unit,
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
                        CameraPreviewWithOverlay(
                            isScanning = state.isScanning,
                            onQrDetected = vm::onQrDetected,
                        )
                        AnimatedVisibility(
                            visible = state.result != null || state.isVerifying,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            ResultPanel(
                                isVerifying = state.isVerifying,
                                result = state.result,
                                onRescan = { vm.resetScan() },
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
            "Paste a credential SD-JWT to verify it manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            label = { Text("SD-JWT credential") },
            placeholder = { Text("eyJ…~…~") },
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
                onClick = { onVerify(input.trim()) },
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
                                                ?.let(onQrDetected)
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ValidGreen.copy(alpha = 0.12f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = ValidGreen,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "✓ Verified Student",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ValidGreen,
            )
            if (result.validUntil != null) {
                Text(
                    "Valid until ${result.validUntil}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (!result.universityId.isNullOrBlank()) {
                Text(
                    "University: ${result.universityId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (result.statusOk == null) {
                Text(
                    "⚠ Status list unreachable — last-known state shown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun InvalidResultContent(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RevokedRed.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Cancel,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = RevokedRed,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Verification Failed",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = RevokedRed,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RevokedResultContent(statusIdx: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RevokedRed.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.GppBad,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = RevokedRed,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Credential REVOKED",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = RevokedRed,
            )
            Text(
                "Status index $statusIdx is marked revoked by the issuer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
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
