package com.example.translator.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.translator.ScreenCaptureService

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

  // Launcher for MediaProjection
  val mediaProjectionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
        putExtra("resultCode", result.resultCode)
        putExtra("data", result.data)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
    }
  }

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text("Translator Control Panel", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(32.dp))

    if (!canDrawOverlays) {
      Button(onClick = {
        val intent = Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
      }) {
        Text("Grant Overlay Permission")
      }
      Spacer(modifier = Modifier.height(16.dp))
    }

    Button(onClick = {
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      context.startActivity(intent)
    }) {
      Text("Enable Accessibility Service")
    }
    Spacer(modifier = Modifier.height(16.dp))

    Button(
      onClick = {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
      },
      enabled = canDrawOverlays
    ) {
      Text("Start Translating")
    }
    
    Button(
      onClick = {
         context.stopService(Intent(context, ScreenCaptureService::class.java))
      },
      modifier = Modifier.padding(top = 16.dp)
    ) {
      Text("Stop Translating")
    }
  }
}
