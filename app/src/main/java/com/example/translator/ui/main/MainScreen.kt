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
import com.example.translator.LearningStore
import com.example.translator.ModelAssetManager
import com.example.translator.ServiceBridge
import com.example.translator.TranslationNotificationManager
import com.example.translator.TranslatorPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()

  // ── Actualizador interno directo (descarga e instalación in-app) ────────────
  val appUpdater = remember { AppUpdater(context) }
  var pendingUpdate by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
  var isCheckingForUpdate by remember { mutableStateOf(false) }
  var isDownloadingUpdate by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf(0f) }
  var downloadStatusText by remember { mutableStateOf("") }
  var downloadedApkFile by remember { mutableStateOf<File?>(null) }
  var downloadErrorMessage by remember { mutableStateOf<String?>(null) }
  var downloadJob by remember { mutableStateOf<Job?>(null) }

  // ── Gestor del modelo neuronal local (Index-Translate-2B GGUF) ──────────────
  var isModelReady by remember { mutableStateOf(ModelAssetManager.isModelReady(context)) }
  var modelDownloadState by remember { mutableStateOf<ModelAssetManager.DownloadState>(ModelAssetManager.DownloadState.Idle) }

  DisposableEffect(Unit) {
      ModelAssetManager.currentListener = { state ->
          modelDownloadState = state
          if (state is ModelAssetManager.DownloadState.Completed) {
              isModelReady = true
          }
      }
      onDispose {
          ModelAssetManager.currentListener = null
      }
  }

  val modelPickerLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.GetContent()
  ) { uri ->
      if (uri != null) {
          coroutineScope.launch {
              val success = ModelAssetManager.importModelFromUri(context, uri)
              isModelReady = ModelAssetManager.isModelReady(context)
              if (success) {
                  android.widget.Toast.makeText(context, "Neural model imported successfully", android.widget.Toast.LENGTH_SHORT).show()
              } else {
                  android.widget.Toast.makeText(context, "Failed to import model", android.widget.Toast.LENGTH_SHORT).show()
              }
          }
      }
  }

  // Por qué: Verifica silenciosamente al iniciar si existe una versión más reciente en GitHub
  LaunchedEffect(Unit) {
      val info = appUpdater.checkForUpdates()
      if (info != null) {
          pendingUpdate = info
          val dest = appUpdater.getUpdateFile(info.version)
          if (dest.exists() && dest.length() > 0L && (info.apkSize == 0L || dest.length() == info.apkSize)) {
              downloadedApkFile = dest
          }
      }
  }

  // Por qué: Muestra un diálogo interactivo para descargar el APK directamente en la app
  // y lanzar el instalador de paquetes sin redirigir al usuario al repositorio ni al navegador web.
  pendingUpdate?.let { info ->
      AlertDialog(
          onDismissRequest = {
              if (!isDownloadingUpdate) {
                  pendingUpdate = null
                  downloadErrorMessage = null
              }
          },
          title = {
              Text(
                  text = when {
                      downloadedApkFile != null -> "Update Ready to Install"
                      isDownloadingUpdate -> "Downloading Update"
                      downloadErrorMessage != null -> "Update Failed"
                      else -> "Update Available"
                  }
              )
          },
          text = {
              Column(modifier = Modifier.fillMaxWidth()) {
                  when {
                      isDownloadingUpdate -> {
                          Text(
                              text = "Downloading ${info.version} directly...",
                              style = MaterialTheme.typography.bodyMedium
                          )
                          Spacer(modifier = Modifier.height(12.dp))
                          LinearProgressIndicator(
                              progress = { downloadProgress },
                              modifier = Modifier.fillMaxWidth()
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              text = downloadStatusText,
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                      }
                      downloadedApkFile != null -> {
                          Text(
                              text = "Version ${info.version} has been downloaded and is ready to install.",
                              style = MaterialTheme.typography.bodyMedium
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              text = "Tap Install to apply the update immediately.",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                      }
                      downloadErrorMessage != null -> {
                          Text(
                              text = downloadErrorMessage ?: "An error occurred during download.",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.error
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              text = "You can retry downloading or open the release page in your browser.",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                      }
                      else -> {
                          val sizeFormatted = if (info.apkSize > 0) {
                              String.format(Locale.US, " (%.1f MB)", info.apkSize / (1024f * 1024f))
                          } else ""
                          Text(
                              text = "Version ${info.version}$sizeFormatted is available.",
                              style = MaterialTheme.typography.bodyMedium
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              text = "Download and update directly within the app?",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                      }
                  }
              }
          },
          confirmButton = {
              when {
                  isDownloadingUpdate -> {
                      // Descargando en progreso
                  }
                  downloadedApkFile != null -> {
                      Button(
                          onClick = {
                              val file = downloadedApkFile
                              if (file != null) {
                                  val result = appUpdater.installApk(context, file)
                                  if (result is AppUpdater.InstallResult.PermissionRequired) {
                                      coroutineScope.launch {
                                          snackbarHostState.showSnackbar("Enable 'Allow from this source' to install update")
                                      }
                                  } else if (result is AppUpdater.InstallResult.Success) {
                                      pendingUpdate = null
                                  }
                              }
                          }
                      ) {
                          Text("Install Now")
                      }
                  }
                  downloadErrorMessage != null -> {
                      Button(
                          onClick = {
                              downloadErrorMessage = null
                              isDownloadingUpdate = true
                              downloadProgress = 0f
                              downloadStatusText = "Connecting..."
                              val destination = appUpdater.getUpdateFile(info.version)
                              downloadJob = coroutineScope.launch {
                                  val success = appUpdater.downloadApk(info.apkUrl, destination) { bytesDownloaded, totalBytes ->
                                      downloadProgress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                                      val mbDownloaded = bytesDownloaded / (1024f * 1024f)
                                      val mbTotal = totalBytes / (1024f * 1024f)
                                      val percent = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                      downloadStatusText = String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", mbDownloaded, mbTotal, percent)
                                  }
                                  isDownloadingUpdate = false
                                  if (success) {
                                      downloadedApkFile = destination
                                      appUpdater.installApk(context, destination)
                                  } else {
                                      downloadErrorMessage = "Download failed. Check your internet connection."
                                  }
                              }
                          }
                      ) {
                          Text("Retry")
                      }
                  }
                  else -> {
                      Button(
                          onClick = {
                              downloadErrorMessage = null
                              isDownloadingUpdate = true
                              downloadProgress = 0f
                              downloadStatusText = "Connecting..."
                              val destination = appUpdater.getUpdateFile(info.version)
                              downloadJob = coroutineScope.launch {
                                  val success = appUpdater.downloadApk(info.apkUrl, destination) { bytesDownloaded, totalBytes ->
                                      downloadProgress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                                      val mbDownloaded = bytesDownloaded / (1024f * 1024f)
                                      val mbTotal = totalBytes / (1024f * 1024f)
                                      val percent = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                      downloadStatusText = String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", mbDownloaded, mbTotal, percent)
                                  }
                                  isDownloadingUpdate = false
                                  if (success) {
                                      downloadedApkFile = destination
                                      appUpdater.installApk(context, destination)
                                  } else {
                                      downloadErrorMessage = "Download failed. Check your internet connection."
                                  }
                              }
                          }
                      ) {
                          Text("Download & Install")
                      }
                  }
              }
          },
          dismissButton = {
              if (isDownloadingUpdate) {
                  TextButton(
                      onClick = {
                          downloadJob?.cancel()
                          isDownloadingUpdate = false
                          downloadStatusText = ""
                      }
                  ) {
                      Text("Cancel")
                  }
              } else {
                  Row {
                      if (downloadErrorMessage != null) {
                          TextButton(
                              onClick = {
                                  appUpdater.openUpdatePage(context, info.htmlUrl)
                              }
                          ) {
                              Text("Open Browser")
                          }
                      }
                      TextButton(
                          onClick = {
                              pendingUpdate = null
                              downloadErrorMessage = null
                          }
                      ) {
                          Text("Later")
                      }
                  }
              }
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
      mutableStateOf(prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true))
  }
  var translationMode by remember {
      mutableStateOf(prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_TAP) ?: AppDefaults.MODE_TAP)
  }
  var aiTranslationEnabled by remember {
      mutableStateOf(prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true))
  }
  // ── Aprendizaje adaptativo local (glosario personal y memoria de frases) ───
  val learningStore = remember { LearningStore.get(context) }
  var learningEnabled by remember {
      mutableStateOf(prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true))
  }
  var learningStats by remember { mutableStateOf(learningStore.stats()) }
  var showResetLearningDialog by remember { mutableStateOf(false) }
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

  val supportedLanguages = remember { com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages() }
  var modelStatus by remember { mutableStateOf("Checking language models...") }

  // Por qué: Descarga de forma bidireccional los modelos locales (rumano a griego y griego a rumano)
  // garantizando que tanto la traducción en pantalla como el asistente inverso del teclado funcionen 100% offline.
  fun ensureModelsDownloaded(source: String, target: String) {
      val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
      val sourceCode = if (source != "AUTO") source else AppDefaults.SOURCE_LANGUAGE
      val clientForward = TranslatorPool.get(sourceCode, target)
      val clientReverse = TranslatorPool.get(target, sourceCode)
      modelStatus = "Downloading language models..."
      clientForward.downloadModelIfNeeded(conditions)
          .addOnSuccessListener {
              clientReverse.downloadModelIfNeeded(conditions)
                  .addOnSuccessListener {
                      modelStatus = "Language models ready (offline)"
                  }
                  .addOnFailureListener {
                      modelStatus = "Model download error. Check connection."
                  }
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
                          "The app encountered an error in the previous session:\n\n$crashLog",
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

  val prioritizedLanguages = remember(supportedLanguages) {
      val top = listOf("ro", "el", "en")
      val rest = supportedLanguages.filter { !top.contains(it) }.sortedBy {
          Locale.forLanguageTag(it).displayLanguage
      }
      top + rest
  }

  val isNotificationRequired = controlInterface == AppDefaults.INTERFACE_NOTIFICATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  val allPermissionsGranted = canDrawOverlays && isAccessibilityEnabled && (!isNotificationRequired || isNotificationPermissionGranted)
  val isServiceActive = ServiceBridge.isTranslatingActive || ServiceBridge.isBubbleRunning || ServiceBridge.isNotificationActive

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { innerPadding ->
    val scrollState = rememberScrollState()
    Column(
      modifier = modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 20.dp, vertical = 20.dp)
          .verticalScroll(scrollState),
      horizontalAlignment = Alignment.Start,
      verticalArrangement = Arrangement.Top
    ) {
      // Cabecera sobria y nativa con versión interactiva
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Column {
              Text(
                  text = "Translator",
                  style = MaterialTheme.typography.headlineMedium,
                  color = MaterialTheme.colorScheme.onBackground
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                  text = "WhatsApp Bilingual Assistant",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )
          }

          Surface(
              shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              // Por qué: Permite al usuario comprobar manualmente actualizaciones al pulsar sobre la versión
              modifier = Modifier.clickable {
                  if (!isCheckingForUpdate && !isDownloadingUpdate) {
                      isCheckingForUpdate = true
                      coroutineScope.launch {
                          val info = appUpdater.checkForUpdates()
                          isCheckingForUpdate = false
                          if (info != null) {
                              pendingUpdate = info
                              val dest = appUpdater.getUpdateFile(info.version)
                              if (dest.exists() && dest.length() > 0L && (info.apkSize == 0L || dest.length() == info.apkSize)) {
                                  downloadedApkFile = dest
                              }
                          } else {
                              snackbarHostState.showSnackbar("You're on the latest version (v${BuildConfig.VERSION_NAME})")
                          }
                      }
                  }
              }
          ) {
              Text(
                  text = if (isCheckingForUpdate) "Checking..." else "v${BuildConfig.VERSION_NAME}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
              )
          }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // ── Sección 1: Configuración de Traducción ────────────────────────────
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = "Translation",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(12.dp))

              // Selector de pares lingüísticos con botón de intercambio simétrico
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Box(modifier = Modifier.weight(1f)) {
                      LanguageDropdown("From", sourceLanguage, listOf("AUTO") + prioritizedLanguages) {
                          sourceLanguage = it
                          prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, it).apply()
                      }
                  }

                  IconButton(
                      onClick = {
                          if (sourceLanguage != "AUTO") {
                              val temp = sourceLanguage
                              sourceLanguage = targetLanguage
                              targetLanguage = temp
                              prefs.edit()
                                  .putString(AppDefaults.KEY_SOURCE_LANGUAGE, sourceLanguage)
                                  .putString(AppDefaults.KEY_TARGET_LANGUAGE, targetLanguage)
                                  .apply()
                          }
                      },
                      enabled = sourceLanguage != "AUTO",
                      modifier = Modifier.padding(horizontal = 4.dp)
                  ) {
                      Text(
                          text = "⇄",
                          style = MaterialTheme.typography.titleMedium,
                          color = if (sourceLanguage != "AUTO") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                      )
                  }

                  Box(modifier = Modifier.weight(1f)) {
                      LanguageDropdown("To", targetLanguage, prioritizedLanguages) {
                          targetLanguage = it
                          prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, it).apply()
                      }
                  }
              }

              Spacer(modifier = Modifier.height(8.dp))

              // Estado de descarga de modelos sin conexión
              Row(verticalAlignment = Alignment.CenterVertically) {
                  val isReady = modelStatus.contains("ready", ignoreCase = true)
                  Surface(
                      modifier = Modifier.size(8.dp),
                      shape = androidx.compose.foundation.shape.CircleShape,
                      color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                  ) {}
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                      text = modelStatus,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
              }

              HorizontalDivider(
                  modifier = Modifier.padding(vertical = 14.dp),
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
              )

              // Modo de traducción de mensajes (reemplaza switch confuso con selectores directos)
              Text(
                  text = "Chat Message Translation",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(8.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  FilterChip(
                      selected = translationMode == AppDefaults.MODE_TAP,
                      onClick = {
                          translationMode = AppDefaults.MODE_TAP
                          prefs.edit().putString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_TAP).apply()
                      },
                      label = { Text("Tap Message") },
                      modifier = Modifier.weight(1f)
                  )
                  FilterChip(
                      selected = translationMode == AppDefaults.MODE_ALL,
                      onClick = {
                          translationMode = AppDefaults.MODE_ALL
                          prefs.edit().putString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL).apply()
                      },
                      label = { Text("Auto-Translate All") },
                      modifier = Modifier.weight(1f)
                  )
              }
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                  text = if (translationMode == AppDefaults.MODE_TAP)
                      "Tap any message in chat to reveal its translation. Leaves timestamps clean."
                  else
                      "Translates all messages on screen automatically as you view them.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )

              HorizontalDivider(
                  modifier = Modifier.padding(vertical = 14.dp),
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
              )

              // Por qué: Traducción neuronal especializada en el dispositivo (Index-Translate-2B)
              // para capturar jerga, modismos e informalidad en 150 idiomas 100% offline y sin claves de API.
              val isAiActive = isModelReady && aiTranslationEnabled
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
              ) {
                  Column(modifier = Modifier.weight(1f)) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                              text = "Neural MT Engine",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurface
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Surface(
                              shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                              color = if (isAiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                          ) {
                              Text(
                                  text = if (isAiActive) "Index-Translate 2B" else "ML Kit Local",
                                  style = MaterialTheme.typography.labelSmall,
                                  color = if (isAiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                              )
                          }
                      }
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = "Dedicated 150-language machine translation model. Understands informal chat, slang, and idioms 100% offline.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Switch(
                      checked = isAiActive,
                      enabled = isModelReady,
                      onCheckedChange = { isChecked ->
                          aiTranslationEnabled = isChecked
                          prefs.edit().putBoolean(AppDefaults.KEY_AI_ENABLED, isChecked).apply()
                      }
                  )
              }

              Spacer(modifier = Modifier.height(10.dp))

              when (val state = modelDownloadState) {
                  is ModelAssetManager.DownloadState.Downloading -> {
                      Column(modifier = Modifier.fillMaxWidth()) {
                          LinearProgressIndicator(
                              progress = { state.progressPercent / 100f },
                              modifier = Modifier.fillMaxWidth().height(6.dp),
                              color = MaterialTheme.colorScheme.primary,
                          )
                          Spacer(modifier = Modifier.height(4.dp))
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.SpaceBetween,
                              verticalAlignment = Alignment.CenterVertically
                          ) {
                              Text(
                                  text = "Downloading: ${state.progressPercent}% (${state.downloadedMB} MB / ${state.totalMB} MB)",
                                  style = MaterialTheme.typography.labelSmall,
                                  color = MaterialTheme.colorScheme.primary
                              )
                              TextButton(
                                  onClick = { ModelAssetManager.cancelDownload() },
                                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                              ) {
                                  Text("Cancel", style = MaterialTheme.typography.labelSmall)
                              }
                          }
                      }
                  }
                  is ModelAssetManager.DownloadState.Error -> {
                      Column(modifier = Modifier.fillMaxWidth()) {
                          Text(
                              text = "Download error: ${state.message}",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.error
                          )
                          Spacer(modifier = Modifier.height(4.dp))
                          Row {
                              Button(
                                  onClick = {
                                      coroutineScope.launch {
                                          ModelAssetManager.startDownload(context)
                                      }
                                  },
                                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                              ) {
                                  Text("Retry Download", style = MaterialTheme.typography.labelSmall)
                              }
                          }
                      }
                  }
                  else -> {
                      if (isModelReady) {
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.SpaceBetween,
                              verticalAlignment = Alignment.CenterVertically
                          ) {
                              Text(
                                  text = "✓ Model file installed (~1.3 GB)",
                                  style = MaterialTheme.typography.labelSmall,
                                  color = MaterialTheme.colorScheme.primary
                              )
                              TextButton(
                                  onClick = {
                                      ModelAssetManager.deleteModel(context)
                                      isModelReady = false
                                  },
                                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                              ) {
                                  Text("Delete Model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                              }
                          }
                      } else {
                          Column(modifier = Modifier.fillMaxWidth()) {
                              Text(
                                  text = "Neural model not downloaded. Tap below to download or import your .gguf file.",
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant
                              )
                              Spacer(modifier = Modifier.height(6.dp))
                              Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                              ) {
                                  Button(
                                      onClick = {
                                          coroutineScope.launch {
                                              ModelAssetManager.startDownload(context)
                                          }
                                      },
                                      modifier = Modifier.weight(1f),
                                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                  ) {
                                      Text("Download (1.3 GB)", style = MaterialTheme.typography.labelSmall)
                                  }
                                  OutlinedButton(
                                      onClick = {
                                          modelPickerLauncher.launch("*/*")
                                      },
                                      modifier = Modifier.weight(1f),
                                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                  ) {
                                      Text("Select .gguf File", style = MaterialTheme.typography.labelSmall)
                                  }
                              }
                          }
                      }
                  }
              }
          }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // ── Sección 1b: Aprendizaje adaptativo ─────────────────────────────────
      LaunchedEffect(Unit) {
          // Por qué: Refresca periódicamente las estadísticas mientras la pantalla está visible
          while (true) {
              learningStats = learningStore.stats()
              delay(3000L)
          }
      }
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
              ) {
                  Column(modifier = Modifier.weight(1f)) {
                      Text(
                          text = "Learn From My Edits",
                          style = MaterialTheme.typography.titleMedium,
                          color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = "When you insert a draft, fix a word and send it, the translator remembers your wording. Everything stays on this phone.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Switch(
                      checked = learningEnabled,
                      onCheckedChange = { isChecked ->
                          learningEnabled = isChecked
                          prefs.edit().putBoolean(AppDefaults.KEY_LEARNING_ENABLED, isChecked).apply()
                      }
                  )
              }
              Spacer(modifier = Modifier.height(10.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Text(
                      text = "${learningStats.first} learned words · ${learningStats.second} remembered phrases",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary
                  )
                  TextButton(
                      onClick = { showResetLearningDialog = true },
                      enabled = learningStats.first + learningStats.second > 0,
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                  ) {
                      Text("Reset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                  }
              }
          }
      }

      if (showResetLearningDialog) {
          AlertDialog(
              onDismissRequest = { showResetLearningDialog = false },
              title = { Text("Reset learned data?") },
              text = { Text("All learned words and remembered phrases will be deleted from this phone.") },
              confirmButton = {
                  TextButton(onClick = {
                      learningStore.clearAll()
                      learningStats = learningStore.stats()
                      showResetLearningDialog = false
                  }) {
                      Text("Reset", color = MaterialTheme.colorScheme.error)
                  }
              },
              dismissButton = {
                  TextButton(onClick = { showResetLearningDialog = false }) { Text("Cancel") }
              }
          )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // ── Sección 2: Asistente de Teclado e Interfaz ─────────────────────────
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Text(
                  text = "Typing & Controls",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(12.dp))

              // Asistente de escritura en teclado
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
              ) {
                  Column(modifier = Modifier.weight(1f)) {
                      Text(
                          text = "Keyboard Assistant",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                          text = "Translates outgoing drafts in real-time above the keyboard while typing.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Switch(
                      checked = keyboardTranslatorEnabled,
                      onCheckedChange = { isChecked ->
                          keyboardTranslatorEnabled = isChecked
                          prefs.edit().putBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, isChecked).apply()
                      }
                  )
              }

              HorizontalDivider(
                  modifier = Modifier.padding(vertical = 14.dp),
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
              )

              // Método de control preferido
              Text(
                  text = "Control Interface",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(8.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  FilterChip(
                      selected = controlInterface == AppDefaults.INTERFACE_NOTIFICATION,
                      onClick = {
                          controlInterface = AppDefaults.INTERFACE_NOTIFICATION
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION).apply()
                      },
                      label = { Text("Notification Menu") },
                      modifier = Modifier.weight(1f)
                  )
                  FilterChip(
                      selected = controlInterface == AppDefaults.INTERFACE_BUBBLE,
                      onClick = {
                          controlInterface = AppDefaults.INTERFACE_BUBBLE
                          prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_BUBBLE).apply()
                      },
                      label = { Text("Floating Bubble") },
                      modifier = Modifier.weight(1f)
                  )
              }
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                  text = if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION)
                      "Controls appear in your notification drawer. Zero screen clutter over chat."
                  else
                      "Shows a movable floating button on your screen over WhatsApp.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
              )
          }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // ── Sección 3: Permisos del Sistema ────────────────────────────────────
      OutlinedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
          colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
          Column(modifier = Modifier.padding(16.dp)) {
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Text(
                      text = "System Permissions",
                      style = MaterialTheme.typography.titleMedium,
                      color = MaterialTheme.colorScheme.onSurface
                  )
                  if (allPermissionsGranted) {
                      Surface(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                          color = MaterialTheme.colorScheme.primaryContainer
                      ) {
                          Text(
                              text = "Ready",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onPrimaryContainer,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                      }
                  }
              }

              Spacer(modifier = Modifier.height(10.dp))

              if (allPermissionsGranted) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          text = "✓",
                          color = MaterialTheme.colorScheme.primary,
                          style = MaterialTheme.typography.titleMedium
                      )
                      Spacer(modifier = Modifier.width(8.dp))
                      Text(
                          text = "All required permissions are enabled and active.",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                  }
              } else {
                  PermissionStatusRow(label = "Display over other apps", granted = canDrawOverlays) {
                      val intent = Intent(
                          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                          Uri.parse("package:${context.packageName}")
                      )
                      context.startActivity(intent)
                  }
                  Spacer(modifier = Modifier.height(8.dp))
                  PermissionStatusRow(label = "Accessibility Service", granted = isAccessibilityEnabled) {
                      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                      context.startActivity(intent)
                  }
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                      Spacer(modifier = Modifier.height(8.dp))
                      PermissionStatusRow(label = "Notifications", granted = isNotificationPermissionGranted) {
                          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                      }
                  }

                  if (!isAccessibilityEnabled) {
                      Spacer(modifier = Modifier.height(10.dp))
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween
                      ) {
                          Text(
                              text = "Android 13+ restricted? App Info → ⋮ → Allow restricted settings.",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.weight(1f)
                          )
                          TextButton(
                              onClick = {
                                  val intent = Intent(
                                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                      Uri.parse("package:${context.packageName}")
                                  )
                                  context.startActivity(intent)
                              }
                          ) {
                              Text("App Info", style = MaterialTheme.typography.labelMedium)
                          }
                      }
                  }
              }
          }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // ── Botón de Acción Principal Dinámico (unificado sin botones redundantes) ──
      if (!allPermissionsGranted) {
          Button(
              modifier = Modifier
                  .fillMaxWidth()
                  .height(50.dp),
              shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
              enabled = false,
              onClick = {}
          ) {
              Text("Grant Required Permissions Above", style = MaterialTheme.typography.bodyLarge)
          }
      } else if (isServiceActive) {
          OutlinedButton(
              modifier = Modifier
                  .fillMaxWidth()
                  .height(50.dp),
              shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
              onClick = {
                  context.stopService(Intent(context, FloatingBubbleService::class.java))
                  TranslationNotificationManager.cancelNotification(context)
                  ServiceBridge.isTranslatingActive = false
                  ServiceBridge.onTranslationStateChanged?.invoke(false)
                  android.widget.Toast.makeText(context, "Translator stopped", android.widget.Toast.LENGTH_SHORT).show()
              }
          ) {
              Text(
                  text = "Stop Translator",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.error
              )
          }
      } else {
          Button(
              modifier = Modifier
                  .fillMaxWidth()
                  .height(50.dp),
              shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
              onClick = {
                  ensureModelsDownloaded(sourceLanguage, targetLanguage)
                  if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) {
                      if (ServiceBridge.isBubbleRunning) {
                          context.stopService(Intent(context, FloatingBubbleService::class.java))
                      }
                      ServiceBridge.isTranslatingActive = true
                      ServiceBridge.onTranslationStateChanged?.invoke(true)
                      TranslationNotificationManager.showNotification(context, isTranslating = true)
                      android.widget.Toast.makeText(context, "Translator active in notification drawer", android.widget.Toast.LENGTH_SHORT).show()
                  } else {
                      TranslationNotificationManager.cancelNotification(context)
                      val intent = Intent(context, FloatingBubbleService::class.java)
                      context.startService(intent)
                      android.widget.Toast.makeText(context, "Floating bubble opened", android.widget.Toast.LENGTH_SHORT).show()
                  }
              }
          ) {
              Text(
                  text = if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) "Start Notification Menu" else "Start Floating Bubble",
                  style = MaterialTheme.typography.bodyLarge
              )
          }
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
