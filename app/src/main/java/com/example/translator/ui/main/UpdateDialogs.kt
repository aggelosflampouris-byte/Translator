package com.example.translator.ui.main

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.translator.AppUpdater
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
                text = when {
                    downloadedApkFile != null -> "Update Ready to Install"
                    isDownloading -> "Downloading Update"
                    downloadErrorMessage != null -> "Update Failed"
                    else -> "Update Available"
                }
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    isDownloading -> {
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
                            text = downloadErrorMessage,
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
                        } else {
                            ""
                        }
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
                isDownloading -> {
                    // Por qué: Evita acciones concurrentes mientras se escribe el APK en disco.
                }
                downloadedApkFile != null -> {
                    Button(onClick = { onInstall(downloadedApkFile) }) {
                        Text("Install Now")
                    }
                }
                else -> {
                    Button(onClick = onDownloadOrRetry) {
                        Text(if (downloadErrorMessage != null) "Retry" else "Download & Install")
                    }
                }
            }
        },
        dismissButton = {
            if (isDownloading) {
                TextButton(onClick = onCancelDownload) {
                    Text("Cancel")
                }
            } else {
                Row {
                    if (downloadErrorMessage != null) {
                        TextButton(onClick = onOpenBrowser) {
                            Text("Open Browser")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Later")
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
        title = { Text("App Crashed") },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = "The app encountered an error in the previous session:\n\n$crashLog",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyLog) {
                Text("Copy Log")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

