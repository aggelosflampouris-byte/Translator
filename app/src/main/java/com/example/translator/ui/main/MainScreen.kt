package com.example.translator.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.translator.AppDefaults
import com.example.translator.BuildConfig
import com.example.translator.ClipboardHelper
import com.example.translator.R
import com.example.translator.TargetApps

/**
 * Pantalla principal de configuración y activación de OverChat.
 *
 * Por qué: Observa flujos reactivos desde [MainViewModel] y delega tarjetas, diálogos y
 * gestión de glosario en componentes especializados sin lógica de negocio en la capa Compose.
 */
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showGlossaryDialog by remember { mutableStateOf(false) }
    var showResetLearningDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MainUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is MainUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is MainUiEvent.ShowInstallPermissionSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Allow unknown apps or open in browser to install update",
                        actionLabel = if (event.htmlUrl != null) "Open Browser" else null,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.htmlUrl?.let(viewModel::openUpdateInBrowser)
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResumeRefreshed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener {
            viewModel.onAccessibilityStateChanged()
        }
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                viewModel.onAccessibilityStateChanged()
            }
        }
        accessibilityManager?.addAccessibilityStateChangeListener(stateListener)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            contentObserver
        )
        onDispose {
            accessibilityManager?.removeAccessibilityStateChangeListener(stateListener)
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }

    uiState.crashLog?.let { currentCrashLog ->
        CrashReportDialog(
            crashLog = currentCrashLog,
            onCopyLog = {
                ClipboardHelper.copySensitiveText(context, "Crash Log", currentCrashLog)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onDismiss = viewModel::dismissCrashLog
        )
    }

    uiState.pendingUpdate?.let { info ->
        AppUpdateDialog(
            info = info,
            isDownloading = uiState.isDownloadingUpdate,
            downloadProgress = uiState.downloadProgress,
            downloadStatusText = uiState.downloadStatusText,
            downloadedApkFile = uiState.downloadedApkFile,
            downloadErrorMessage = uiState.downloadErrorMessage,
            onDownloadOrRetry = { viewModel.downloadUpdate(info) },
            onInstall = viewModel::installUpdateFile,
            onCancelDownload = viewModel::cancelUpdateDownload,
            onOpenBrowser = { viewModel.openUpdateInBrowser(info.htmlUrl) },
            onDismiss = viewModel::dismissUpdateDialog
        )
    }

    val isNotificationRequired = prefs.controlInterface == AppDefaults.INTERFACE_NOTIFICATION &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val allPermissionsGranted = uiState.isAccessibilityEnabled &&
        (!isNotificationRequired || uiState.isNotificationPermissionGranted)
    val isServiceActive = runtime.isAnyServiceActive

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            MainActionBottomBar(
                allPermissionsGranted = allPermissionsGranted,
                isServiceActive = isServiceActive,
                controlInterface = prefs.controlInterface,
                onStart = viewModel::startService,
                onStop = viewModel::stopService
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()

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
                isCheckingForUpdate = uiState.isCheckingForUpdate,
                onCheckUpdates = { viewModel.checkForUpdates(manual = true) }
            )

            if (showDiagnosticsDialog) {
                ScanDiagnosticsDialog(
                    sourceLanguage = prefs.sourceLanguage,
                    targetLanguage = prefs.targetLanguage,
                    translationMode = prefs.translationMode,
                    enabledPackages = prefs.enabledPackages,
                    onCopied = {
                        showDiagnosticsDialog = false
                        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showDiagnosticsDialog = false }
                )
            }

            TranslationSettingsCard(
                sourceLanguage = prefs.sourceLanguage,
                targetLanguage = prefs.targetLanguage,
                availableLanguages = AppDefaults.AVAILABLE_LANGUAGES,
                modelStatus = uiState.modelStatus,
                translationMode = prefs.translationMode,
                glossaryEnabled = prefs.glossaryEnabled,
                settingsEnabled = true,
                onSourceSelected = viewModel::setSourceLanguage,
                onTargetSelected = viewModel::setTargetLanguage,
                onSwapLanguages = viewModel::swapLanguages,
                onTranslationModeChanged = viewModel::setTranslationMode,
                onGlossaryChanged = viewModel::setGlossaryEnabled
            )

            TypingAndControlsCard(
                keyboardTranslatorEnabled = prefs.keyboardTranslatorEnabled,
                learningEnabled = prefs.learningEnabled,
                learningStats = uiState.learningStats,
                controlInterface = prefs.controlInterface,
                autoCheckUpdates = prefs.autoCheckUpdates,
                idleAutoPauseMinutes = prefs.idleAutoPauseMinutes,
                settingsEnabled = true,
                onKeyboardTranslatorChanged = viewModel::setKeyboardTranslatorEnabled,
                onLearningChanged = viewModel::setLearningEnabled,
                onOpenGlossary = {
                    viewModel.refreshGlossarySnapshot()
                    showGlossaryDialog = true
                },
                onRequestResetLearning = { showResetLearningDialog = true },
                onControlInterfaceChanged = viewModel::setControlInterface,
                onAutoCheckUpdatesChanged = viewModel::setAutoCheckUpdates,
                onIdleAutoPauseChanged = viewModel::setIdleAutoPauseEnabled
            )

            if (showGlossaryDialog) {
                GlossaryDialog(
                    sourceLanguage = prefs.sourceLanguage,
                    targetLanguage = prefs.targetLanguage,
                    corrections = uiState.glossaryCorrections,
                    memories = uiState.glossaryMemories,
                    onAddCorrection = viewModel::addGlossaryCorrection,
                    onDeleteCorrection = viewModel::deleteGlossaryCorrection,
                    onDeleteMemory = viewModel::deleteGlossaryMemory,
                    onDismiss = { showGlossaryDialog = false }
                )
            }

            if (showResetLearningDialog) {
                ResetLearningDialog(
                    onConfirm = {
                        viewModel.resetLearning()
                        showResetLearningDialog = false
                    },
                    onDismiss = { showResetLearningDialog = false }
                )
            }

            SupportedAppsAndThemeCard(
                enabledPackages = prefs.enabledPackages,
                overlayTheme = prefs.overlayTheme,
                settingsEnabled = true,
                onManageApps = { onItemClick(TargetApps) },
                onOverlayThemeChanged = viewModel::setOverlayTheme
            )

            SystemPermissionsCard(
                isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                isNotificationRequired = isNotificationRequired,
                isNotificationPermissionGranted = uiState.isNotificationPermissionGranted,
                allPermissionsGranted = allPermissionsGranted,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { showDiagnosticsDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.perm_diagnostics),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreenHeader(
    isServiceActive: Boolean,
    isCheckingForUpdate: Boolean,
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
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (isServiceActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.status_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                            text = stringResource(R.string.action_grant_permissions),
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
                            text = stringResource(R.string.action_stop_service),
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
                        val actionRes = when (controlInterface) {
                            AppDefaults.INTERFACE_NOTIFICATION -> R.string.action_start_notification
                            AppDefaults.INTERFACE_BUBBLE -> R.string.action_start_sphere
                            else -> R.string.action_start_translation
                        }
                        Text(
                            text = stringResource(actionRes),
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
