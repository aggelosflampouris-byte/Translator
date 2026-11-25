package com.example.translator.ui.main

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.translator.AppUpdater
import com.example.translator.R
import java.io.File
import java.util.Locale

/**
 * Diálogo modal de descarga e instalación directa de actualizaciones desde GitHub Releases.
 *
 * Por qué: Separa los estados visuales de descarga, reintento e instalación del flujo principal
 * de [MainScreen] sin redirigir al navegador web salvo error explícito.
 */
@Composable
fun AppUpdateDialog(
    info: AppUpdater.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadStatusText: String,
    downloadedApkFile: File?,
    downloadErrorMessage: String?,
    onDownloadOrRetry: () -> Unit,
    onInstall: (File) -> Unit,
    onCancelDownload: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        title = {
            Text(
                text = stringResource(
                    when {
                        downloadedApkFile != null -> R.string.update_title_ready
                        isDownloading -> R.string.update_title_downloading
                        downloadErrorMessage != null -> R.string.update_title_failed
                        else -> R.string.update_title_available
                    }
                )
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    isDownloading -> {
                        Text(
                            text = stringResource(R.string.update_downloading_version, info.version),
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
                            text = stringResource(R.string.update_ready_message, info.version),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_ready_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    downloadErrorMessage != null -> {
                        Text(
                            text = downloadErrorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_error_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        val sizeFormatted = if (info.apkSize > 0) {
                            String.format(Locale.US, " (%.1f MB)", info.apkSize / (1024f * 1024f))
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource(R.string.update_available_message, info.version, sizeFormatted),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_available_question),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                isDownloading -> {
                    // Por qué: Evita acciones concurrentes mientras se escribe el APK en disco.
                }
                downloadedApkFile != null -> {
                    Button(onClick = { onInstall(downloadedApkFile) }) {
                        Text(stringResource(R.string.action_install_now))
                    }
                }
                else -> {
                    Button(onClick = onDownloadOrRetry) {
                        Text(
                            stringResource(
                                if (downloadErrorMessage != null) R.string.action_retry else R.string.action_download_install
                            )
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (isDownloading) {
                TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else {
                Row {
                    if (downloadErrorMessage != null) {
                        TextButton(onClick = onOpenBrowser) {
                            Text(stringResource(R.string.action_open_browser))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_later))
                    }
                }
            }
        }
    )
}

/**
 * Diálogo para mostrar y copiar el registro de un fallo fatal ocurrido en la sesión previa.
 */
@Composable
fun CrashReportDialog(
    crashLog: String,
    onCopyLog: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = stringResource(R.string.crash_dialog_message, crashLog),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyLog) {
                Text(stringResource(R.string.action_copy_log))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    )
}

/**
 * Conector reutilizable de eventos Toast/Snackbar y diálogo de actualización entre pantallas.
 *
 * Por qué: Garantiza que iniciar una búsqueda manual desde Ajustes o Inicio muestre exactamente
 * los mismos avisos y cuadro de descarga sin duplicar lógica entre pantallas.
 */
@Composable
fun MainUiEventsAndUpdateHost(
    viewModel: MainViewModel,
    uiState: MainUiState,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val installPermissionMessage = stringResource(R.string.snackbar_install_permission)
    val openBrowserLabel = stringResource(R.string.action_open_browser)

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
                        message = installPermissionMessage,
                        actionLabel = if (event.htmlUrl != null) openBrowserLabel else null,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.htmlUrl?.let(viewModel::openUpdateInBrowser)
                    }
                }
            }
        }
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
}

