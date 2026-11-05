package com.example.translator.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.translator.AppDefaults
import com.example.translator.AppUpdater
import com.example.translator.BuildConfig
import com.example.translator.FloatingBubbleService
import com.example.translator.ScreenCaptureService
import com.example.translator.ServiceBridge
import com.example.translator.TranslatorPool
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

  // ── Update check ──────────────────────────────────────────────────────────
  var pendingUpdate by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
  var updateError by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
      val info = AppUpdater(context).checkForUpdates()
      if (info != null) pendingUpdate = info
  }

  pendingUpdate?.let { info ->
      AlertDialog(
          onDismissRequest = { pendingUpdate = null },
          title = { Text("Update Available") },
          text = { Text("Version ${info.version} is available. Download and install now?") },
          confirmButton = {
              TextButton(onClick = {
                  pendingUpdate = null
                  AppUpdater(context).downloadAndInstall(
                      activityContext = context,
                      updateInfo = info,
                      onError = { msg -> updateError = msg }
                  )
              }) { Text("Update") }
          },
          dismissButton = {
              TextButton(onClick = { pendingUpdate = null }) { Text("Later") }
          }
      )
  }

  updateError?.let { msg ->
      AlertDialog(
          onDismissRequest = { updateError = null },
          title = { Text("Update Failed") },
          text = { Text(msg) },
          confirmButton = {
              TextButton(onClick = { updateError = null }) { Text("OK") }
          }
      )
  }

  val prefs = remember { context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE) }
  var sourceLanguage by remember {
      mutableStateOf(prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE)
  }
  var targetLanguage by remember {
      mutableStateOf(prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE)
  }
  var keyboardTranslatorEnabled by remember {
      mutableStateOf(prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, false))
  }

  val supportedLanguages = remember { com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages() }
  var modelStatus by remember { mutableStateOf("Checking language models...") }

  fun ensureModelsDownloaded(source: String, target: String) {
      val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
      val sourceCode = if (source != "AUTO") source else AppDefaults.SOURCE_LANGUAGE
      val client = TranslatorPool.get(sourceCode, target)
      modelStatus = "Downloading language models..."
      client.downloadModelIfNeeded(conditions)
          .addOnSuccessListener {
              modelStatus = "Language models ready (offline)"
          }
          .addOnFailureListener {
              modelStatus = "Model download error. Check connection."
          }
  }

  LaunchedEffect(sourceLanguage, targetLanguage) {
      if (!prefs.contains(AppDefaults.KEY_SOURCE_LANGUAGE)) {
          prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, sourceLanguage).apply()
      }
      if (!prefs.contains(AppDefaults.KEY_TARGET_LANGUAGE)) {
          prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, targetLanguage).apply()
      }
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
    val scrollState = rememberScrollState()
    Column(
      modifier = modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 20.dp, vertical = 24.dp)
          .verticalScroll(scrollState),
      horizontalAlignment = Alignment.Start,
      verticalArrangement = Arrangement.Top
    ) {
      // Cabecera sobria y nativa con versión discreta
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text(
              text = "Translator",
              style = MaterialTheme.typography.headlineLarge,
              color = MaterialTheme.colorScheme.onBackground
          )
          Surface(
              shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant
          ) {
              Text(
                  text = "v${BuildConfig.VERSION_NAME}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
          }
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = "Instant, offline translation assistant for WhatsApp",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(24.dp))

      // Sección de configuración de idiomas
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(
              containerColor = MaterialTheme.colorScheme.surface
          )
      ) {
          Column(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp)
          ) {
              Text(
                  text = "Translation Pair",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(12.dp))

              LanguageDropdown("Source Language", sourceLanguage, listOf("AUTO") + supportedLanguages) {
                  sourceLanguage = it
                  prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, it).apply()
              }
              Spacer(modifier = Modifier.height(10.dp))
              LanguageDropdown("Target Language", targetLanguage, supportedLanguages) {
                  targetLanguage = it
                  prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, it).apply()
              }
              Spacer(modifier = Modifier.height(10.dp))
              Row(
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  val isReady = modelStatus.contains("ready", ignoreCase = true)
                  Surface(
                      modifier = Modifier.size(8.dp),
                      shape = androidx.compose.foundation.shape.CircleShape,
                      color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                  ) {}
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                      text = modelStatus,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
              }
          }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Asistente de escritura en teclado
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(
              containerColor = MaterialTheme.colorScheme.surface
          )
      ) {
          Row(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
          ) {
              Column(modifier = Modifier.weight(1f)) {
                  Text(
                      text = "Keyboard Assistant",
                      style = MaterialTheme.typography.titleMedium,
                      color = MaterialTheme.colorScheme.onSurface
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                      text = "Show translation pill above keyboard while typing",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
              }
              Spacer(modifier = Modifier.width(16.dp))
              Switch(
                  checked = keyboardTranslatorEnabled,
                  onCheckedChange = { isChecked ->
                      keyboardTranslatorEnabled = isChecked
                      prefs.edit().putBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, isChecked).apply()
                  }
              )
          }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Sección de permisos del sistema
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(
              containerColor = MaterialTheme.colorScheme.surface
          )
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = "System Permissions",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(12.dp))

              PermissionStatusRow(label = "Display over other apps", granted = canDrawOverlays) {
                  val intent = Intent(
                      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      Uri.parse("package:${context.packageName}")
                  )
                  context.startActivity(intent)
              }
              Spacer(modifier = Modifier.height(10.dp))
              PermissionStatusRow(label = "Accessibility service", granted = isAccessibilityEnabled) {
                  val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                  context.startActivity(intent)
              }
          }
      }

      // Guía discreta para Android 13+ con restricciones
      if (!isAccessibilityEnabled) {
          Spacer(modifier = Modifier.height(12.dp))
          OutlinedCard(
              modifier = Modifier.fillMaxWidth(),
              shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
              colors = CardDefaults.outlinedCardColors(
                  containerColor = MaterialTheme.colorScheme.surface
              )
          ) {
              Column(modifier = Modifier.padding(16.dp)) {
                  Text(
                      "Android 13+ Restricted Settings",
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onSurface
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                      "If you see 'App access denied', open App Info → tap ⋮ → " +
                      "'Allow restricted settings', authenticate, then return here.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
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

      Spacer(modifier = Modifier.height(28.dp))

      // Botón principal de activación
      val allPermissionsGranted = canDrawOverlays && isAccessibilityEnabled
      Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
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
            else "Grant permissions above to start",
            style = MaterialTheme.typography.bodyLarge
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        onClick = {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
      ) {
        Text("Stop Translator", style = MaterialTheme.typography.bodyLarge)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                color = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = if (granted) "Active" else "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!granted) {
            TextButton(
                onClick = onFix,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Enable", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

fun checkAccessibilityEnabled(context: Context): Boolean {
    // Primary check: if the bridge reports the service is active, trust it
    if (ServiceBridge.isAccessibilityActive) {
        return true
    }

    val serviceClassName = "com.example.translator.TranslationAccessibilityService"

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
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
        it.resolveInfo.serviceInfo.name == serviceClassName
    }
}
