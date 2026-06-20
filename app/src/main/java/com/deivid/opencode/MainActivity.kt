package com.deivid.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid.opencode.data.SetupPreferences
import com.deivid.opencode.ui.screens.HomeScreen
import com.deivid.opencode.ui.screens.SetupScreen
import com.deivid.opencode.ui.theme.OpenCodeTheme
import com.deivid.opencode.viewmodel.ServerViewModel
import com.deivid.opencode.viewmodel.SetupViewModel

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored — server runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ requires the POST_NOTIFICATIONS permission to keep
        // a foreground service notification visible.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val setupPreferences = SetupPreferences(this)

        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // observe the persisted setup state — null = DataStore still loading
                    val setupData by setupPreferences.data.collectAsState(initial = null)

                    val data = setupData
                    if (data == null) {
                        LoadingScreen()
                    } else if (!data.completed) {
                        val viewModel: SetupViewModel = viewModel()
                        SetupScreen(
                            viewModel = viewModel,
                            onComplete = { /* state will flip and re-render */ },
                        )
                    } else {
                        val viewModel: ServerViewModel = viewModel()
                        // Bind to the foreground service once and keep it bound.
                        // The server itself isn't started here — that happens
                        // when the user taps "Start server".
                        val ctx = this
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            viewModel.bind(ctx)
                        }
                        HomeScreen(
                            viewModel = viewModel,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
