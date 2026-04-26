package hr.fer.studentzkp.holder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import hr.fer.studentzkp.holder.navigation.AppNavHost
import hr.fer.studentzkp.holder.navigation.BottomNavBar
import hr.fer.studentzkp.holder.navigation.Screen
import hr.fer.studentzkp.holder.ui.theme.StudentZKTheme
import hr.fer.studentzkp.holder.util.HolderKeyManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() -- re-enable once icon is finalized
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure the holder's hardware-bound key pair exists
        HolderKeyManager.ensureKeyExists(this)
        setContent {
            StudentZKTheme {
                StudentZKApp()
            }
        }
    }
}

@Composable
fun StudentZKApp() {
    val navController = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { BottomNavBar(navController) },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
        )
    }
}
