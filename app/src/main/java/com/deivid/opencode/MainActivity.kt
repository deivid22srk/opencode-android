package com.deivid.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid.opencode.ui.screens.HomeScreen
import com.deivid.opencode.ui.theme.OpenCodeTheme
import com.deivid.opencode.viewmodel.ServerViewModel

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
                    val viewModel: ServerViewModel = viewModel()
                    LaunchedEffect(Unit) {
                        viewModel.bind(this@MainActivity)
                    }
                    HomeScreen(
                        viewModel = viewModel,
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The view-model doesn't own the service; let it outlive the activity
        // so the server keeps running when the user backgrounds the app.
    }
}
