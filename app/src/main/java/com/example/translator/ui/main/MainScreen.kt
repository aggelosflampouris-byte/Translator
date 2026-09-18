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

import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  
  // State for permissions
  var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
  var showPermissionDialog by remember { mutableStateOf(false) }

  // Check permissions on launch and resume
  DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
              canDrawOverlays = Settings.canDrawOverlays(context)
              isAccessibilityEnabled = checkAccessibilityEnabled(context)
              if (!canDrawOverlays || !isAccessibilityEnabled) {
                  showPermissionDialog = true
              } else {
                  showPermissionDialog = false
              }
          }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose {
          lifecycleOwner.lifecycle.removeObserver(observer)
      }
  }

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

  if (showPermissionDialog) {
      AlertDialog(
          onDismissRequest = { showPermissionDialog = false },
          title = { Text("Permissions Required") },
          text = { Text("The Translator app requires 'Display over other apps' and 'Accessibility' permissions to function. Please grant them on the next screens.") },
          confirmButton = {
              TextButton(onClick = {
                  showPermissionDialog = false
                  if (!canDrawOverlays) {
                      val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                      context.startActivity(intent)
                  } else if (!isAccessibilityEnabled) {
                      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                      context.startActivity(intent)
                  }
              }) {
                  Text("Grant Permissions")
              }
          }
      )
  }

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text("Translator Control Panel", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(32.dp))

    // Restricted Settings Notice for Android 13+
    if (!isAccessibilityEnabled) {
        Card(
            modifier = Modifier.padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ Restricted Settings (Android 13+)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "You CANNOT enable Accessibility yet. You will get an 'App access denied' error. To fix this Android 13+ security lock on ANY device:\n\n" +
                    "1. Tap 'Open App Info' below.\n" +
                    "2. Look for 'Allow restricted settings'.\n" +
                    "   • For most (Pixel/Samsung/Moto): Tap the 3 dots (⋮) in the top right -> 'Allow restricted settings'.\n" +
                    "   • For Xiaomi/POCO/Redmi: Scroll to the very bottom of the page -> 'Allow restricted settings'.\n" +
                    "3. Authenticate (fingerprint/PIN), then come back here to enable Accessibility.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text("Open App Info")
                }
            }
        }
    }

    Button(
      onClick = {
        if (!canDrawOverlays) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        } else if (!isAccessibilityEnabled) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        } else {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
      }
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

fun checkAccessibilityEnabled(context: Context): Boolean {
    var accessibilityEnabled = 0
    val service = context.packageName + "/" + com.example.translator.TranslationAccessibilityService::class.java.canonicalName
    try {
        accessibilityEnabled = Settings.Secure.getInt(
            context.applicationContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
    } catch (e: Settings.SettingNotFoundException) {
        // Assume disabled
    }
    
    if (accessibilityEnabled == 1) {
        val settingValue = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (settingValue != null) {
            val splitter = android.text.TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            while (splitter.hasNext()) {
                val accessibilityService = splitter.next()
                if (accessibilityService.equals(service, ignoreCase = true)) {
                    return true
                }
            }
        }
    }
    return false
}
