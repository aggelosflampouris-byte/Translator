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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation3.runtime.NavKey
import com.example.translator.ScreenCaptureService
import com.example.translator.TranslationAccessibilityService
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val snackbarHostState = remember { SnackbarHostState() }

  // Permission state — refreshed on every resume
  var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }

  // Refresh permissions when activity resumes (user may have just returned from Settings)
  DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
              canDrawOverlays = Settings.canDrawOverlays(context)
              isAccessibilityEnabled = checkAccessibilityEnabled(context)
          }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Poll for accessibility service binding (async — can take a few seconds after enabling)
  LaunchedEffect(isAccessibilityEnabled) {
      if (!isAccessibilityEnabled) {
          while (true) {
              delay(500)
              if (checkAccessibilityEnabled(context)) {
                  isAccessibilityEnabled = true
                  break
              }
          }
      }
  }

  // Launcher for MediaProjection screen capture consent
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

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { innerPadding ->
    Column(
      modifier = modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text("Translator Control Panel", style = MaterialTheme.typography.headlineMedium)
      Spacer(modifier = Modifier.height(32.dp))

      // Per-permission status rows with individual Fix buttons
      PermissionStatusRow(label = "Display over other apps", granted = canDrawOverlays) {
          val intent = Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:${context.packageName}")
          )
          context.startActivity(intent)
      }
      Spacer(modifier = Modifier.height(8.dp))
      PermissionStatusRow(label = "Accessibility service", granted = isAccessibilityEnabled) {
          val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
          context.startActivity(intent)
      }

      // Restricted settings guidance — only shown when accessibility is not yet enabled
      if (!isAccessibilityEnabled) {
          Spacer(modifier = Modifier.height(16.dp))
          Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.errorContainer
              )
          ) {
              Column(modifier = Modifier.padding(16.dp)) {
                  Text(
                      "⚠️ Android 13+ Restricted Settings",
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onErrorContainer
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                      "If you see 'App access denied', open App Info → tap ⋮ → " +
                      "'Allow restricted settings', authenticate, then return here.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onErrorContainer
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  OutlinedButton(
                      onClick = {
                          val intent = Intent(
                              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                              Uri.parse("package:${context.packageName}")
                          )
                          context.startActivity(intent)
                      }
                  ) { Text("Open App Info") }
              }
          }
      }

      Spacer(modifier = Modifier.height(32.dp))

      // Start Translating — disabled (not hidden) until all permissions are granted.
      // This prevents the button from silently redirecting the user to settings.
      val allPermissionsGranted = canDrawOverlays && isAccessibilityEnabled
      Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = allPermissionsGranted,
        onClick = {
            val mediaProjectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
      ) {
        Text(
            if (allPermissionsGranted) "Start Translating"
            else "Grant permissions above to start"
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
      ) {
        Text("Stop Translating")
      }
    }
  }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (granted) "✅" else "❌", modifier = Modifier.padding(end = 8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (!granted) {
            TextButton(onClick = onFix) { Text("Fix") }
        }
    }
}

fun checkAccessibilityEnabled(context: Context): Boolean {
    // Ultimate fallback: if the live service instance is active, trust it
    if (TranslationAccessibilityService.isSharedInstanceActive) {
        return true
    }

    val expectedComponentName = android.content.ComponentName(
        context,
        TranslationAccessibilityService::class.java
    )

    // Method 1: Read Settings.Secure directly (most reliable on OEM ROMs)
    val settingValue = Settings.Secure.getString(
        context.applicationContext.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    if (settingValue != null) {
        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(settingValue)
        while (splitter.hasNext()) {
            if (splitter.next().contains(context.packageName, ignoreCase = true)) {
                return true
            }
        }
    }

    // Method 2: Fallback to AccessibilityManager query
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == expectedComponentName.packageName &&
        it.resolveInfo.serviceInfo.name == expectedComponentName.className
    }
}
