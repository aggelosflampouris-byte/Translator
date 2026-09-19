package com.example.translator.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation3.runtime.NavKey
import com.example.translator.FloatingBubbleService
import com.example.translator.ScreenCaptureService
import com.example.translator.TranslationAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val snackbarHostState = remember { SnackbarHostState() }

  val prefs = context.getSharedPreferences("translator_prefs", Context.MODE_PRIVATE)
  var sourceLanguage by remember {
      mutableStateOf(prefs.getString("source_language", com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN) ?: com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN)
  }
  var targetLanguage by remember {
      mutableStateOf(prefs.getString("target_language", com.google.mlkit.nl.translate.TranslateLanguage.GREEK) ?: com.google.mlkit.nl.translate.TranslateLanguage.GREEK)
  }

  val supportedLanguages = remember { com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages() }
  var modelStatus by remember { mutableStateOf("Checking language models...") }

  fun ensureModelsDownloaded(source: String, target: String) {
      val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
      val sourceCode = if (source != "AUTO") source else com.google.mlkit.nl.translate.TranslateLanguage.ROMANIAN
      val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
          .setSourceLanguage(sourceCode)
          .setTargetLanguage(target)
          .build()
      val client = com.google.mlkit.nl.translate.Translation.getClient(options)
      modelStatus = "Downloading language models..."
      client.downloadModelIfNeeded(conditions)
          .addOnSuccessListener {
              modelStatus = "✓ Language Models Ready (Offline)"
          }
          .addOnFailureListener {
              modelStatus = "⚠️ Model download pending (Check internet)"
          }
  }

  LaunchedEffect(sourceLanguage, targetLanguage) {
      ensureModelsDownloaded(sourceLanguage, targetLanguage)
  }

  // Permission state — refreshed on every resume
  var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }

  // Crash Reporting
  val crashPrefs = context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
  var crashLog by remember { mutableStateOf(crashPrefs.getString("last_crash", null)) }

  if (crashLog != null) {
      AlertDialog(
          onDismissRequest = {
              crashPrefs.edit().remove("last_crash").apply()
              crashLog = null
          },
          title = { Text("App Crashed") },
          text = { 
              androidx.compose.foundation.lazy.LazyColumn {
                  item {
                      Text(
                          "The app crashed during the last session. Please send this log to the developer:\n\n\$crashLog",
                          style = MaterialTheme.typography.bodySmall
                      )
                  }
              }
          },
          confirmButton = {
              TextButton(onClick = {
                  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                  val clip = android.content.ClipData.newPlainText("Crash Log", crashLog)
                  clipboard.setPrimaryClip(clip)
                  android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
              }) {
                  Text("Copy Log")
              }
          },
          dismissButton = {
              TextButton(onClick = {
                  crashPrefs.edit().remove("last_crash").apply()
                  crashLog = null
              }) {
                  Text("Dismiss")
              }
          }
      )
  }

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

      // Language Selection
      LanguageDropdown("Source Language", sourceLanguage, listOf("AUTO") + supportedLanguages) {
          sourceLanguage = it
          prefs.edit().putString("source_language", it).apply()
      }
      Spacer(modifier = Modifier.height(8.dp))
      LanguageDropdown("Target Language", targetLanguage, supportedLanguages) {
          targetLanguage = it
          prefs.edit().putString("target_language", it).apply()
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
          text = modelStatus,
          style = MaterialTheme.typography.bodySmall,
          color = if (modelStatus.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
      )

      Spacer(modifier = Modifier.height(20.dp))

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
            ensureModelsDownloaded(sourceLanguage, targetLanguage)
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.startService(intent)
            android.widget.Toast.makeText(
                context,
                "Floating menu opened. Switch to WhatsApp!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
      ) {
        Text(
            if (allPermissionsGranted) "Start Floating Translator"
            else "Grant permissions above to start"
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
      ) {
        Text("Stop Translator")
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(label: String, selectedCode: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    val getDisplayName = { code: String ->
        if (code == "AUTO") "Auto-Detect"
        else Locale.forLanguageTag(code).displayLanguage.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            readOnly = true,
            value = getDisplayName(selectedCode),
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(getDisplayName(code)) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
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
