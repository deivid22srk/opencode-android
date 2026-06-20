package com.deivid.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid.opencode.data.SetupPreferences
import com.deivid.opencode.ui.screens.HomeScreen
import com.deivid.opencode.ui.screens.SetupScreen
import com.deivid.opencode.ui.theme.OpenCodeTheme
import com.deivid.opencode.viewmodel.ServerViewModel
import com.deivid.opencode.viewmodel.SetupViewModel
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored — server runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ requires the POST_NOTIFICATIONS permission to keep
        // a foreground service notification visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRouter(activity = this)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

/**
 * Decides whether to show the [SetupScreen] or [HomeScreen].
 * Reads the DataStore flag on first composition and then stays on
 * whichever screen is appropriate.
 */
@Composable
private fun AppRouter(activity: ComponentActivity) {
    var showSetup by remember { mutableStateOf<Boolean?>(null) }
    val setupPrefs = remember { SetupPreferences(activity) }

    // One-shot check — stays on whatever screen it lands on
    LaunchedEffect(Unit) {
        showSetup = !setupPrefs.isComplete()
    }

    when (showSetup) {
        null -> {
            // Still loading the DataStore preference
            /* no-op, blank screen */
        }
        true -> {
            val setupViewModel: SetupViewModel = viewModel()
            SetupScreen(
                viewModel = setupViewModel,
                onComplete = {
                    // When setup completes, switch to the home screen.
                    // The SetupViewModel already persisted the flag.
                    showSetup = false
                },
            )
        }
        false -> {
            val serverViewModel: ServerViewModel = viewModel()
            LaunchedEffect(Unit) {
                serverViewModel.bind(activity)
            }
            HomeScreen(
                viewModel = serverViewModel,
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}