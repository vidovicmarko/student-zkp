package hr.fer.studentzkp.holder.ui.verify

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standalone verify result screen — navigated to from Scan tab when deeper
 * detail is needed. The ScanScreen already shows inline results; this is a
 * fallback full-screen destination kept for deep-link or future use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyResultScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verification Result") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Result shown inline on the Scan tab.", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onBack) { Text("Go Back") }
            }
        }
    }
}
