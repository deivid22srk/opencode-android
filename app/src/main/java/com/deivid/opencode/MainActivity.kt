package com.deivid.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid.opencode.data.local.SetupDataStore
import com.deivid.opencode.ui.screens.HomeScreen
import com.deivid.opencode.ui.screens.SetupScreen
import com.deivid.opencode.ui.theme.OpenCodeTheme
import com.deivid.opencode.viewmodel.ServerViewModel

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored — server runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    AppEntryPoint()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun AppEntryPoint() {
    val context = LocalContext.current
    var showSetup by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val store = SetupDataStore(context)
        showSetup = !store.isSetupCompleted()
    }

    when (showSetup) {
        null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        true -> {
            SetupScreen(
                onSetupComplete = { showSetup = false },
            )
        }
        false -> {
            val viewModel: ServerViewModel = viewModel()
            LaunchedEffect(Unit) {
                viewModel.bind(context)
            }
            HomeScreen(
                viewModel = viewModel,
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}
