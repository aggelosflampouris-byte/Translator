package com.example.translator.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.translator.R
import com.example.translator.ServiceBridge
import com.example.translator.TargetAppCatalog
import com.example.translator.TargetApps
import com.example.translator.TranslationNotificationManager
import com.example.translator.TranslatorApplication
import com.example.translator.TranslatorPool
import com.google.mlkit.common.model.DownloadConditions
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla principal de configuración y activación de OverChat.
 *
 * Por qué: Mantiene únicamente el estado de pantalla, la descarga de modelos y la barra inferior fija,
 * delegando tarjetas y diálogos a componentes especializados.
 */
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val appUpdater = remember { AppUpdater.get(context) }
    var pendingUpdate by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
    var isCheckingForUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var downloadErrorMessage by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Por qué: Verifica silenciosamente al iniciar si existe una versión más reciente en GitHub.
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

    fun handleInstallResult(result: AppUpdater.InstallResult) {
        when (result) {
            is AppUpdater.InstallResult.Success -> pendingUpdate = null
            is AppUpdater.InstallResult.PermissionRequired -> coroutineScope.launch {
                snackbarHostState.showSnackbar("Enable 'Allow from this source' to install update")
            }
            is AppUpdater.InstallResult.Error -> {
                downloadedApkFile = null
                downloadErrorMessage = result.message
            }
            is AppUpdater.InstallResult.FileNotFound -> {
                downloadedApkFile = null
                downloadErrorMessage = "The downloaded update file is missing."
            }
        }
    }

    fun startUpdateDownload(info: AppUpdater.UpdateInfo) {
        downloadErrorMessage = null
        isDownloadingUpdate = true
        downloadProgress = 0f
        downloadStatusText = "Connecting..."
        val destination = appUpdater.getUpdateFile(info.version)
        downloadJob = coroutineScope.launch {
            val success = appUpdater.downloadApk(info.apkUrl, destination, info.apkSize) { bytesDownloaded, totalBytes ->
                downloadProgress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                val mbDownloaded = bytesDownloaded / (1024f * 1024f)
                val mbTotal = totalBytes / (1024f * 1024f)
                val percent = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                downloadStatusText = String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", mbDownloaded, mbTotal, percent)
            }
            isDownloadingUpdate = false
            if (success) {
                downloadedApkFile = destination
                handleInstallResult(appUpdater.installApk(context, destination))
            } else {
                downloadErrorMessage = "Download failed. Check your internet connection."
            }
        }
    }

    pendingUpdate?.let { info ->
        AppUpdateDialog(
            info = info,
            isDownloading = isDownloadingUpdate,
            downloadProgress = downloadProgress,
            downloadStatusText = downloadStatusText,
            downloadedApkFile = downloadedApkFile,
            downloadErrorMessage = downloadErrorMessage,
            onDownloadOrRetry = { startUpdateDownload(info) },
            onInstall = { file -> handleInstallResult(appUpdater.installApk(context, file)) },
            onCancelDownload = {
                downloadJob?.cancel()
                isDownloadingUpdate = false
                downloadStatusText = ""
            },
            onOpenBrowser = { appUpdater.openUpdatePage(context, info.htmlUrl) },
            onDismiss = {
                pendingUpdate = null
                downloadErrorMessage = null
            }
        )
    }

    val prefs = remember { context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE) }
    val availableLanguages = AppDefaults.AVAILABLE_LANGUAGES
    var sourceLanguage by remember {
        val saved = prefs.getString(AppDefaults.KEY_SOURCE_LANGUAGE, AppDefaults.SOURCE_LANGUAGE) ?: AppDefaults.SOURCE_LANGUAGE
        mutableStateOf(if (availableLanguages.contains(saved)) saved else AppDefaults.SOURCE_LANGUAGE)
    }
    var targetLanguage by remember {
        val saved = prefs.getString(AppDefaults.KEY_TARGET_LANGUAGE, AppDefaults.TARGET_LANGUAGE) ?: AppDefaults.TARGET_LANGUAGE
        mutableStateOf(if (availableLanguages.contains(saved)) saved else AppDefaults.TARGET_LANGUAGE)
    }
    var keyboardTranslatorEnabled by remember {
        mutableStateOf(prefs.getBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, true))
    }
    var translationMode by remember {
        mutableStateOf(prefs.getString(AppDefaults.KEY_TRANSLATE_MODE, AppDefaults.MODE_ALL) ?: AppDefaults.MODE_ALL)
    }
    var glossaryEnabled by remember {
        mutableStateOf(prefs.getBoolean(AppDefaults.KEY_AI_ENABLED, true))
    }

    val learningStore = remember { LearningStore.get(context) }
    var learningEnabled by remember {
        mutableStateOf(prefs.getBoolean(AppDefaults.KEY_LEARNING_ENABLED, true))
    }
    var learningStats by remember { mutableStateOf(learningStore.stats()) }
    var showResetLearningDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var controlInterface by remember {
        mutableStateOf(prefs.getString(AppDefaults.KEY_CONTROL_INTERFACE, AppDefaults.INTERFACE_NOTIFICATION) ?: AppDefaults.INTERFACE_NOTIFICATION)
    }
    var enabledPackages by remember {
        mutableStateOf(TargetAppCatalog.getEnabledPackages(prefs, context.packageName))
    }
    var overlayTheme by remember {
        mutableStateOf(
            prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO)
                ?: AppDefaults.OVERLAY_THEME_AUTO
        )
    }

    // Por qué: Sincroniza los cambios hechos en pantallas secundarias (como TargetAppsScreen).
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                AppDefaults.KEY_ENABLED_PACKAGES -> {
                    enabledPackages = TargetAppCatalog.getEnabledPackages(prefs, context.packageName)
                }
                AppDefaults.KEY_OVERLAY_THEME -> {
                    overlayTheme = prefs.getString(AppDefaults.KEY_OVERLAY_THEME, AppDefaults.OVERLAY_THEME_AUTO)
                        ?: AppDefaults.OVERLAY_THEME_AUTO
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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

    var modelStatus by remember { mutableStateOf("Checking language models...") }

    // Por qué: Descarga los modelos en ambos sentidos (pantalla y borrador de teclado) para operar 100% offline.
    fun ensureModelsDownloaded(source: String, target: String) {
        val conditions = DownloadConditions.Builder().build()
        val sourceCode = source.takeIf { it in availableLanguages } ?: AppDefaults.SOURCE_LANGUAGE
        val clientForward = TranslatorPool.get(sourceCode, target)
        val clientReverse = TranslatorPool.get(target, sourceCode)
        modelStatus = "Downloading language models..."
        clientForward.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                TranslatorPool.markPairReady(sourceCode, target)
                clientReverse.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        TranslatorPool.markPairReady(target, sourceCode)
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

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }

    val crashPrefs = remember {
        context.getSharedPreferences(TranslatorApplication.CRASH_PREFS, Context.MODE_PRIVATE)
    }
    var crashLog by remember {
        mutableStateOf(crashPrefs.getString(TranslatorApplication.KEY_LAST_CRASH, null))
    }

    crashLog?.let { currentCrashLog ->
        CrashReportDialog(
            crashLog = currentCrashLog,
            onCopyLog = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", currentCrashLog))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                crashPrefs.edit().remove(TranslatorApplication.KEY_LAST_CRASH).apply()
                crashLog = null
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                isAccessibilityEnabled = checkAccessibilityEnabled(context)
                enabledPackages = TargetAppCatalog.getEnabledPackages(prefs, context.packageName)
                ServiceBridge.notifyUiStateChanged()
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

    val isNotificationRequired = controlInterface == AppDefaults.INTERFACE_NOTIFICATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val allPermissionsGranted = canDrawOverlays && isAccessibilityEnabled && (!isNotificationRequired || isNotificationPermissionGranted)
    var isServiceActive by remember { mutableStateOf(ServiceBridge.isAnyServiceActive()) }

    DisposableEffect(Unit) {
        ServiceBridge.onUiStateChanged = {
            isServiceActive = ServiceBridge.isAnyServiceActive()
        }
        onDispose {
            ServiceBridge.onUiStateChanged = null
        }
    }

    // Por qué: El botón principal permanece fijo en la barra inferior para iniciar o detener sin desplazarse.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            MainActionBottomBar(
                allPermissionsGranted = allPermissionsGranted,
                isServiceActive = isServiceActive,
                controlInterface = controlInterface,
                onStart = {
                    ensureModelsDownloaded(sourceLanguage, targetLanguage)
                    if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) {
                        if (ServiceBridge.isBubbleRunning) {
                            context.stopService(Intent(context, FloatingBubbleService::class.java))
                            ServiceBridge.isBubbleRunning = false
                        }
                        ServiceBridge.isTranslatingActive = true
                        ServiceBridge.onTranslationStateChanged?.invoke(true)
                        TranslationNotificationManager.showNotification(context, isTranslating = true)
                        isServiceActive = true
                        Toast.makeText(context, "OverChat active in notification drawer", Toast.LENGTH_SHORT).show()
                    } else {
                        TranslationNotificationManager.cancelNotification(context)
                        ServiceBridge.isBubbleRunning = true
                        context.startService(Intent(context, FloatingBubbleService::class.java))
                        ServiceBridge.notifyUiStateChanged()
                        isServiceActive = true
                        Toast.makeText(context, "Floating bubble opened", Toast.LENGTH_SHORT).show()
                    }
                },
                onStop = {
                    context.stopService(Intent(context, FloatingBubbleService::class.java))
                    TranslationNotificationManager.cancelNotification(context)
                    ServiceBridge.isBubbleRunning = false
                    ServiceBridge.isNotificationActive = false
                    ServiceBridge.isTranslatingActive = false
                    ServiceBridge.onTranslationStateChanged?.invoke(false)
                    ServiceBridge.notifyUiStateChanged()
                    isServiceActive = false
                    Toast.makeText(context, "OverChat stopped", Toast.LENGTH_SHORT).show()
                }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        val settingsEnabled = !isServiceActive

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MainScreenHeader(
                isServiceActive = isServiceActive,
                isCheckingForUpdate = isCheckingForUpdate,
                onOpenDiagnostics = { showDiagnosticsDialog = true },
                onCheckUpdates = {
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
            )

            if (showDiagnosticsDialog) {
                ScanDiagnosticsDialog(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    enabledPackages = enabledPackages,
                    onCopied = {
                        showDiagnosticsDialog = false
                        coroutineScope.launch { snackbarHostState.showSnackbar("Diagnostics copied") }
                    },
                    onDismiss = { showDiagnosticsDialog = false }
                )
            }

            TranslationSettingsCard(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                availableLanguages = availableLanguages,
                modelStatus = modelStatus,
                translationMode = translationMode,
                glossaryEnabled = glossaryEnabled,
                settingsEnabled = settingsEnabled,
                onSourceSelected = { selected ->
                    if (selected == targetLanguage) {
                        targetLanguage = sourceLanguage
                        prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, targetLanguage).apply()
                    }
                    sourceLanguage = selected
                    prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, selected).apply()
                },
                onTargetSelected = { selected ->
                    if (selected == sourceLanguage) {
                        sourceLanguage = targetLanguage
                        prefs.edit().putString(AppDefaults.KEY_SOURCE_LANGUAGE, sourceLanguage).apply()
                    }
                    targetLanguage = selected
                    prefs.edit().putString(AppDefaults.KEY_TARGET_LANGUAGE, selected).apply()
                },
                onSwapLanguages = {
                    val temp = sourceLanguage
                    sourceLanguage = targetLanguage
                    targetLanguage = temp
                    prefs.edit()
                        .putString(AppDefaults.KEY_SOURCE_LANGUAGE, sourceLanguage)
                        .putString(AppDefaults.KEY_TARGET_LANGUAGE, targetLanguage)
                        .apply()
                },
                onTranslationModeChanged = { mode ->
                    translationMode = mode
                    prefs.edit().putString(AppDefaults.KEY_TRANSLATE_MODE, mode).apply()
                },
                onGlossaryChanged = { isChecked ->
                    glossaryEnabled = isChecked
                    prefs.edit().putBoolean(AppDefaults.KEY_AI_ENABLED, isChecked).apply()
                }
            )

            LaunchedEffect(Unit) {
                while (true) {
                    learningStats = learningStore.stats()
                    delay(3000L)
                }
            }

            TypingAndControlsCard(
                keyboardTranslatorEnabled = keyboardTranslatorEnabled,
                learningEnabled = learningEnabled,
                learningStats = learningStats,
                controlInterface = controlInterface,
                settingsEnabled = settingsEnabled,
                onKeyboardTranslatorChanged = { isChecked ->
                    keyboardTranslatorEnabled = isChecked
                    prefs.edit().putBoolean(AppDefaults.KEY_KEYBOARD_ENABLED, isChecked).apply()
                },
                onLearningChanged = { isChecked ->
                    learningEnabled = isChecked
                    prefs.edit().putBoolean(AppDefaults.KEY_LEARNING_ENABLED, isChecked).apply()
                },
                onRequestResetLearning = { showResetLearningDialog = true },
                onControlInterfaceChanged = { selected ->
                    controlInterface = selected
                    prefs.edit().putString(AppDefaults.KEY_CONTROL_INTERFACE, selected).apply()
                }
            )

            if (showResetLearningDialog) {
                ResetLearningDialog(
                    onConfirm = {
                        learningStore.clearAll()
                        learningStats = learningStore.stats()
                        showResetLearningDialog = false
                    },
                    onDismiss = { showResetLearningDialog = false }
                )
            }

            SupportedAppsAndThemeCard(
                enabledPackages = enabledPackages,
                overlayTheme = overlayTheme,
                settingsEnabled = settingsEnabled,
                onManageApps = { onItemClick(TargetApps) },
                onOverlayThemeChanged = { value ->
                    overlayTheme = value
                    prefs.edit().putString(AppDefaults.KEY_OVERLAY_THEME, value).apply()
                }
            )

            SystemPermissionsCard(
                canDrawOverlays = canDrawOverlays,
                isAccessibilityEnabled = isAccessibilityEnabled,
                isNotificationPermissionGranted = isNotificationPermissionGranted,
                allPermissionsGranted = allPermissionsGranted,
                onRequestOverlayPermission = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                onRequestAccessibilityPermission = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onRequestNotificationPermission = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onOpenAppInfo = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
private fun MainScreenHeader(
    isServiceActive: Boolean,
    isCheckingForUpdate: Boolean,
    onOpenDiagnostics: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_overchat_logo),
                contentDescription = "OverChat Logo",
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "OverChat",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (isServiceActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Locked",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "Real-Time Chat Translator",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable(onClick = onOpenDiagnostics)
            ) {
                Text(
                    text = "Debug",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable(onClick = onCheckUpdates)
            ) {
                Text(
                    text = if (isCheckingForUpdate) "Checking..." else "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun MainActionBottomBar(
    allPermissionsGranted: Boolean,
    isServiceActive: Boolean,
    controlInterface: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            when {
                !allPermissionsGranted -> {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = false,
                        onClick = {}
                    ) {
                        Text(
                            text = "Grant Required Permissions Above",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                isServiceActive -> {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        onClick = onStop
                    ) {
                        Text(
                            text = "Stop OverChat",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        onClick = onStart
                    ) {
                        Text(
                            text = if (controlInterface == AppDefaults.INTERFACE_NOTIFICATION) {
                                "Start Notification Menu"
                            } else {
                                "Start Floating Bubble"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
