package com.example.translator.ui.main

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.translator.AppDefaults
import com.example.translator.AppUpdater
import com.example.translator.BuildConfig
import com.example.translator.LearnedCorrectionEntry
import com.example.translator.LearnedMemoryEntry
import com.example.translator.LearningStore
import com.example.translator.ServiceBridge
import com.example.translator.TranslationNotificationManager
import com.example.translator.TranslationPipeline
import com.example.translator.TranslationTileService
import com.example.translator.TranslatorApplication
import com.example.translator.TranslatorPreferences
import com.example.translator.TranslatorPreferencesRepository
import com.example.translator.TranslatorRuntimeState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado observable de la pantalla principal desacoplado de detalles de Compose.
 */
data class MainUiState(
    val isAccessibilityEnabled: Boolean = false,
    val isNotificationPermissionGranted: Boolean = true,
    val modelStatus: String = "Checking language models...",
    val learningStats: Pair<Int, Int> = 0 to 0,
    val glossaryCorrections: List<LearnedCorrectionEntry> = emptyList(),
    val glossaryMemories: List<LearnedMemoryEntry> = emptyList(),
    val pendingUpdate: AppUpdater.UpdateInfo? = null,
    val isCheckingForUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatusText: String = "",
    val downloadedApkFile: File? = null,
    val downloadErrorMessage: String? = null,
    val crashLog: String? = null
)

/**
 * Eventos puntuales de un solo consumo emitidos por [MainViewModel].
 */
sealed interface MainUiEvent {
    data class ShowToast(val message: String) : MainUiEvent
    data class ShowSnackbar(val message: String) : MainUiEvent
    data class ShowInstallPermissionSnackbar(val htmlUrl: String?) : MainUiEvent
}

/**
 * ViewModel principal que centraliza preferencias reactivas, ciclo de vida del servicio,
 * descarga de modelos ML Kit, glosario local y actualizaciones.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferencesRepository = TranslatorPreferencesRepository(appContext)
    private val learningStore = LearningStore.get(appContext)
    private val appUpdater = AppUpdater.get(appContext)
    private val crashPrefs = appContext.getSharedPreferences(TranslatorApplication.CRASH_PREFS, Context.MODE_PRIVATE)

    val preferences: StateFlow<TranslatorPreferences> = preferencesRepository.preferences
    val runtimeState: StateFlow<TranslatorRuntimeState> = ServiceBridge.runtimeState

    private val _uiState = MutableStateFlow(
        MainUiState(
            isAccessibilityEnabled = checkAccessibilityEnabled(appContext),
            isNotificationPermissionGranted = checkNotificationPermissionGranted(),
            learningStats = learningStore.stats(),
            crashLog = crashPrefs.getString(TranslatorApplication.KEY_LAST_CRASH, null)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    private var downloadJob: Job? = null

    init {
        val initial = preferences.value
        ensureModelsDownloaded(initial.sourceLanguage, initial.targetLanguage)
        if (initial.autoCheckUpdates) {
            checkForUpdates(manual = false)
        }
    }

    fun onResumeRefreshed() {
        preferencesRepository.refresh()
        _uiState.update {
            it.copy(
                isAccessibilityEnabled = checkAccessibilityEnabled(appContext),
                isNotificationPermissionGranted = checkNotificationPermissionGranted(),
                learningStats = learningStore.stats()
            )
        }
    }

    fun onAccessibilityStateChanged() {
        _uiState.update { it.copy(isAccessibilityEnabled = checkAccessibilityEnabled(appContext)) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(isNotificationPermissionGranted = granted) }
    }

    fun setSourceLanguage(selected: String) {
        preferencesRepository.setSourceLanguage(selected)
        val updated = preferences.value
        ensureModelsDownloaded(updated.sourceLanguage, updated.targetLanguage)
    }

    fun setTargetLanguage(selected: String) {
        preferencesRepository.setTargetLanguage(selected)
        val updated = preferences.value
        ensureModelsDownloaded(updated.sourceLanguage, updated.targetLanguage)
    }

    fun swapLanguages() {
        preferencesRepository.swapLanguages()
        val updated = preferences.value
        ensureModelsDownloaded(updated.sourceLanguage, updated.targetLanguage)
    }

    fun setTranslationMode(mode: String) = preferencesRepository.setTranslationMode(mode)
    fun setGlossaryEnabled(enabled: Boolean) = preferencesRepository.setGlossaryEnabled(enabled)
    fun setKeyboardTranslatorEnabled(enabled: Boolean) = preferencesRepository.setKeyboardTranslatorEnabled(enabled)
    fun setLearningEnabled(enabled: Boolean) = preferencesRepository.setLearningEnabled(enabled)
    fun setAutoCheckUpdates(enabled: Boolean) = preferencesRepository.setAutoCheckUpdates(enabled)
    fun setIdleAutoPauseEnabled(enabled: Boolean) = preferencesRepository.setIdleAutoPauseEnabled(enabled)
    fun setOverlayTheme(theme: String) = preferencesRepository.setOverlayTheme(theme)

    fun setControlInterface(selected: String) {
        preferencesRepository.setControlInterface(selected)
        if (runtimeState.value.isAnyServiceActive) {
            syncActiveControlSurface(selected)
        }
    }

    fun startService() {
        val current = preferences.value
        ensureModelsDownloaded(current.sourceLanguage, current.targetLanguage)
        val startTranslating = current.controlInterface != AppDefaults.INTERFACE_BUBBLE
        ServiceBridge.isTranslatingActive = startTranslating
        ServiceBridge.onTranslationStateChanged?.invoke(startTranslating)
        syncActiveControlSurface(current.controlInterface)
        val toastMessage = when (current.controlInterface) {
            AppDefaults.INTERFACE_NOTIFICATION -> "OverChat active in notification drawer"
            AppDefaults.INTERFACE_BUBBLE -> "Floating sphere opened"
            else -> "OverChat active (Quick Settings tile)"
        }
        _events.tryEmit(MainUiEvent.ShowToast(toastMessage))
    }

    fun stopService() {
        ServiceBridge.sessionController.stopAllSurfaces()
        TranslationNotificationManager.cancelNotification(appContext)
        ServiceBridge.onBubbleVisibilityChanged?.invoke(false)
        ServiceBridge.onTranslationStateChanged?.invoke(false)
        TranslationTileService.requestSync(appContext)
        ServiceBridge.notifyUiStateChanged()
        _events.tryEmit(MainUiEvent.ShowToast("OverChat stopped"))
    }

    fun refreshGlossarySnapshot() {
        _uiState.update {
            it.copy(
                learningStats = learningStore.stats(),
                glossaryCorrections = learningStore.listCorrections(),
                glossaryMemories = learningStore.listMemories()
            )
        }
    }

    fun addGlossaryCorrection(src: String, tgt: String, wrongTerm: String, preferredTerm: String): Boolean {
        val added = learningStore.addCustomCorrection(src, tgt, wrongTerm, preferredTerm)
        if (added) {
            ServiceBridge.onLearningDataCleared?.invoke()
            refreshGlossarySnapshot()
        }
        return added
    }

    fun deleteGlossaryCorrection(entry: LearnedCorrectionEntry) {
        if (learningStore.deleteCorrection(entry.sourceLang, entry.targetLang, entry.rejected)) {
            ServiceBridge.onLearningDataCleared?.invoke()
            refreshGlossarySnapshot()
        }
    }

    fun deleteGlossaryMemory(entry: LearnedMemoryEntry) {
        if (learningStore.deleteMemory(entry.key)) {
            ServiceBridge.onLearningDataCleared?.invoke()
            refreshGlossarySnapshot()
        }
    }

    fun resetLearning() {
        learningStore.clearAll()
        ServiceBridge.onLearningDataCleared?.invoke()
        refreshGlossarySnapshot()
    }

    fun dismissCrashLog() {
        crashPrefs.edit().remove(TranslatorApplication.KEY_LAST_CRASH).apply()
        _uiState.update { it.copy(crashLog = null) }
    }

    fun checkForUpdates(manual: Boolean) {
        val state = _uiState.value
        if (state.isCheckingForUpdate || state.isDownloadingUpdate) return
        if (manual) {
            _uiState.update { it.copy(isCheckingForUpdate = true) }
        }
        viewModelScope.launch {
            val info = appUpdater.checkForUpdates()
            val readyFile = if (info != null) appUpdater.findReadyUpdateFile(info) else null
            _uiState.update {
                it.copy(
                    isCheckingForUpdate = false,
                    pendingUpdate = info ?: it.pendingUpdate,
                    downloadedApkFile = readyFile ?: it.downloadedApkFile
                )
            }
            if (manual && info == null) {
                _events.tryEmit(
                    MainUiEvent.ShowSnackbar("You're on the latest version (v${BuildConfig.VERSION_NAME})")
                )
            }
        }
    }

    fun downloadUpdate(info: AppUpdater.UpdateInfo) {
        downloadJob?.cancel()
        _uiState.update {
            it.copy(
                downloadErrorMessage = null,
                isDownloadingUpdate = true,
                downloadProgress = 0f,
                downloadStatusText = "Connecting..."
            )
        }
        val destination = appUpdater.getUpdateFile(info.version)
        downloadJob = viewModelScope.launch {
            val success = appUpdater.downloadApk(
                apkUrl = info.apkUrl,
                destinationFile = destination,
                expectedSize = info.apkSize,
                sha256Url = info.sha256Url,
                expectedSha256 = info.expectedSha256
            ) { bytesDownloaded, totalBytes ->
                val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                val mbDownloaded = bytesDownloaded / (1024f * 1024f)
                val mbTotal = totalBytes / (1024f * 1024f)
                val percent = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                _uiState.update {
                    it.copy(
                        downloadProgress = progress,
                        downloadStatusText = String.format(
                            Locale.US,
                            "%.1f MB / %.1f MB (%d%%)",
                            mbDownloaded,
                            mbTotal,
                            percent
                        )
                    )
                }
            }
            _uiState.update { it.copy(isDownloadingUpdate = false) }
            if (success) {
                _uiState.update { it.copy(downloadedApkFile = destination) }
                installUpdateFile(destination)
            } else {
                _uiState.update {
                    it.copy(downloadErrorMessage = "Download failed. Check your internet connection.")
                }
            }
        }
    }

    fun installUpdateFile(file: File) {
        when (val result = appUpdater.installApk(appContext, file)) {
            is AppUpdater.InstallResult.Success -> _uiState.update { it.copy(pendingUpdate = null) }
            is AppUpdater.InstallResult.PermissionRequired -> {
                _events.tryEmit(MainUiEvent.ShowInstallPermissionSnackbar(_uiState.value.pendingUpdate?.htmlUrl))
            }
            is AppUpdater.InstallResult.Error -> _uiState.update {
                it.copy(downloadedApkFile = null, downloadErrorMessage = result.message)
            }
            is AppUpdater.InstallResult.FileNotFound -> _uiState.update {
                it.copy(downloadedApkFile = null, downloadErrorMessage = "The downloaded update file is missing.")
            }
        }
    }

    fun cancelUpdateDownload() {
        downloadJob?.cancel()
        _uiState.update { it.copy(isDownloadingUpdate = false, downloadStatusText = "") }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(pendingUpdate = null, downloadErrorMessage = null) }
    }

    fun openUpdateInBrowser(url: String) {
        appUpdater.openUpdatePage(appContext, url)
    }

    private fun ensureModelsDownloaded(source: String, target: String) {
        _uiState.update { it.copy(modelStatus = "Downloading language models...") }
        TranslationPipeline.ensureModelsDownloaded(
            source = source,
            target = target,
            onSuccess = {
                _uiState.update { it.copy(modelStatus = "Language models ready (offline)") }
            },
            onError = {
                _uiState.update { it.copy(modelStatus = "Model download error. Check connection.") }
            }
        )
    }

    private fun syncActiveControlSurface(controlInterface: String) {
        when (controlInterface) {
            AppDefaults.INTERFACE_NOTIFICATION -> {
                ServiceBridge.isNotificationActive = true
                ServiceBridge.isBubbleRunning = false
                ServiceBridge.onBubbleVisibilityChanged?.invoke(false)
                TranslationNotificationManager.showNotification(
                    appContext,
                    isTranslating = ServiceBridge.isTranslatingActive
                )
            }
            AppDefaults.INTERFACE_BUBBLE -> {
                ServiceBridge.isNotificationActive = false
                TranslationNotificationManager.cancelNotification(appContext)
                ServiceBridge.isBubbleRunning = true
                ServiceBridge.onBubbleVisibilityChanged?.invoke(true)
            }
            else -> {
                ServiceBridge.isNotificationActive = false
                TranslationNotificationManager.cancelNotification(appContext)
                ServiceBridge.isBubbleRunning = false
                ServiceBridge.onBubbleVisibilityChanged?.invoke(false)
            }
        }
        TranslationTileService.requestSync(appContext)
        ServiceBridge.notifyUiStateChanged()
    }

    private fun checkNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        preferencesRepository.close()
    }
}

