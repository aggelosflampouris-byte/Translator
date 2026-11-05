package com.example.translator.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.example.translator.AppDefaults
import com.example.translator.AppUpdater
import com.example.translator.BuildConfig
import com.example.translator.FloatingBubbleService
import com.example.translator.ServiceBridge
import com.example.translator.TranslationNotificationManager
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

  LaunchedEffect(Unit) {
      val info = AppUpdater(context).checkForUpdates()
      if (info != null) pendingUpdate = info
  }

  pendingUpdate?.let { info ->
      AlertDialog(
          onDismissRequest = { pendingUpdate = null },
          title = { Text("Update Available") },
          text = { Text("Version ${info.version} is available on GitHub. Open download page?") },
          confirmButton = {
              TextButton(onClick = {
                  val target = pendingUpdate
                  pendingUpdate = null
                  if (target != null) {
                      AppUpdater(context).openUpdatePage(context, target)
                  }
              }) { Text("View Update") }
          },
          dismissButton = {
              TextButton(onClick = { pendingUpdate = null }) { Text("Later") }
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
  var translationMode by remember {
      mutableStateOf(prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_TAP) ?: AppDefaults.MODE_TAP)
  }
  var aiTranslationEnabled by remember {
      mutableStateOf(prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true))
  }
  var controlInterface by remember {
      mutableStateOf(prefs.getString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION) ?: AppDefaults.INTERFACE_NOTIFICATION)
  }
  var isNotificationPermissionGranted by remember {
      mutableStateOf(
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.POST_NOTIFICATIONS
              ) == PackageManager.PERMISSION_GRANTED
          } else {
              true
          }
      )
  }
  val notificationPermissionLauncher = rememberLauncherForActivityResult(
      ActivityResultContracts.RequestPermission()
  ) { granted ->
      isNotificationPermissionGranted = granted
  }

  // Por qué: Limpia cualquier clave de API que pudiera haber sido guardada previamente en SharedPreferences
  // para evitar que datos sensibles de autenticación queden en el almacenamiento local.
  LaunchedEffect(Unit) {
      if (prefs.contains(AppDefaults.KEY_GEMINI_API_KEY)) {
          prefs.edit().remove(AppDefaults.KEY_GEMINI_API_KEY).apply()
      }
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
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  isNotificationPermissionGranted = ContextCompat.checkSelfPermission(
                      context,
                      Manifest.permission.POST_NOTIFICATIONS
                  ) == PackageManager.PERMISSION_GRANTED
              }
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

      // Modo de interacción para traducción de mensajes
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(
              containerColor = MaterialTheme.colorScheme.surface
          )
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = "Chat Translation Mode",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
              ) {
                  Column(modifier = Modifier.weight(1f)) {
                      Text(
                          text = if (translationMode == AppDefaults.MODE_TAP) "Tap to Translate (Recommended)" else "Auto-Translate All",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = if (translationMode == AppDefaults.MODE_TAP)
                              "Tap any message in WhatsApp to see its translation pill. Leaves timestamps and other messages clean."
                          else
                              "Overlays translations on all visible messages automatically.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Switch(
                      checked = translationMode == AppDefaults.MODE_TAP,
                      onCheckedChange = { isTap ->
                          translationMode = if (isTap) AppDefaults.MODE_TAP else AppDefaults.MODE_ALL
                          prefs.edit().putString(AppDefaults.KEY_TRANSLATE_MODE, translationMode).apply()
                      }
                  )
              }
          }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Por qué: Proporciona control de activación para la traducción con IA en la nube sin exponer
      // jamás la clave de API ni permitir que el usuario visualice credenciales sensibles del desarrollador.
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
                  val isAiConfigured = BuildConfig.GEMINI_API_KEY.isNotBlank()
                  val isAiActive = isAiConfigured && aiTranslationEnabled
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          text = "AI Slang & Logic",
                          style = MaterialTheme.typography.titleMedium,
                          color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.width(8.dp))
                      Surface(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                          color = if (isAiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                      ) {
                          Text(
                              text = if (isAiActive) "Gemini 3.5 Active" else "ML Kit Offline",
                              style = MaterialTheme.typography.labelSmall,
                              color = if (isAiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                      }
                  }
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                      text = if (isAiActive)
                          "Using cloud AI for conversational slang, swear words, and contextual logic."
                      else
                          "Using standard offline dictionary translation.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Switch(
                  checked = aiTranslationEnabled && BuildConfig.GEMINI_API_KEY.isNotBlank(),
                  enabled = BuildConfig.GEMINI_API_KEY.isNotBlank(),
                  onCheckedChange = { isChecked ->
                      aiTranslationEnabled = isChecked
                      prefs.edit().putBoolean(AppDefaults.KEY_AI_ENABLED, isChecked).apply()
                  }
              )
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

      // Por qué: Permite al usuario elegir entre el menú nativo en la barra de notificaciones
      // (sin obstrucción visual en pantalla) o la tradicional burbuja flotante sobre el chat.
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(
              containerColor = MaterialTheme.colorScheme.surface
          )
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = "Control Interface",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                  text = "Select how you want to trigger and control translations",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(12.dp))

              // Opción 1: Menú de Notificación del Sistema (Recomendado)
              Row(
                  modifier = Modifier
                      .fillMaxWidth()
                      .clickable {
                          controlInterface = AppDefaults.INTERFACE_NOTIFICATION
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION).apply()
                      },
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  RadioButton(
                      selected = controlInterface == AppDefaults.INTERFACE_NOTIFICATION,
                      onClick = {
                          controlInterface = AppDefaults.INTERFACE_NOTIFICATION
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION).apply()
                      }
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                              text = "Notification Menu",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurface
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Surface(
                              shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                              color = MaterialTheme.colorScheme.primaryContainer
                          ) {
                              Text(
                                  text = "Recommended",
                                  style = MaterialTheme.typography.labelSmall,
                                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                              )
                          }
                      }
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = "Zero screen clutter. Scan screen & toggle from notification drawer.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
              }

              Spacer(modifier = Modifier.height(8.dp))

              // Opción 2: Burbuja Flotante
              Row(
                  modifier = Modifier
                      .fillMaxWidth()
                      .clickable {
                          controlInterface = AppDefaults.INTERFACE_BUBBLE
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_BUBBLE).apply()
                      },
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  RadioButton(
                      selected = controlInterface == AppDefaults.INTERFACE_BUBBLE,
                      onClick = {
                          controlInterface = AppDefaults.INTERFACE_BUBBLE
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_BUBBLE).apply()
                      }
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column {
                      Text(
                          text = "Floating Screen Bubble",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = "Traditional draggable floating button over WhatsApp.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
              }
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
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  Spacer(modifier = Modifier.height(10.dp))
                  PermissionStatusRow(label = "Notification drawer", granted = isNotificationPermissionGranted) {
                      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  }
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
      val isNotificationRequired = controlInterface == AppDefaults.INTERFACE_NOTIFICATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      val allPermissionsGranted = canDrawOverlays && isAccessibilityEnabled && (!isNotificationRequired || isNotificationPermissionGranted)
      Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        enabled = allPermissionsGranted,
        onClick = {
            ensureModelsDownloaded(sourceLanguage, targetLanguage)
            if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) {
                // Por qué: Activa la sesión de traducción y despliega el menú en el panel de notificaciones
                // evitando bloquear la lectura de mensajes en WhatsApp con iconos flotantes.
                if (ServiceBridge.isBubbleRunning) {
                    context.stopService(Intent(context, FloatingBubbleService::class.java))
                }
                ServiceBridge.isTranslatingActive = true
                ServiceBridge.onTranslationStateChanged?.invoke(true)
                TranslationNotificationManager.showNotification(context, isTranslating = true)
                android.widget.Toast.makeText(
                    context,
                    "Translation menu active in notification drawer! Switch to WhatsApp.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                TranslationNotificationManager.cancelNotification(context)
                val intent = Intent(context, FloatingBubbleService::class.java)
                context.startService(intent)
                android.widget.Toast.makeText(
                    context,
                    "Floating bubble opened. Switch to WhatsApp!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
      ) {
        Text(
            if (!allPermissionsGranted) "Grant permissions above to start"
            else if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) "Start Notification Menu"
            else "Start Floating Translator",
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
            // Por qué: Detiene de forma segura ambos modos (burbuja y notificación) y limpia las superposiciones
            context.stopService(Intent(context, FloatingBubbleService::class.java))
            TranslationNotificationManager.cancelNotification(context)
            ServiceBridge.isTranslatingActive = false
            ServiceBridge.onTranslationStateChanged?.invoke(false)
            android.widget.Toast.makeText(context, "Translation stopped", android.widget.Toast.LENGTH_SHORT).show()
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
