package com.example.translator.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.translator.BuildConfig
import com.example.translator.ServiceBridge
import com.example.translator.TranslatorApplication

/**
 * Diálogo con el informe diagnóstico sin PII del servicio de accesibilidad y primer plano.
 *
 * Por qué: Reúne en un solo reporte los contadores y razones de aborto del motor de escaneo
 * para depurar OEMs específicos sin exponer texto de mensajes privados.
 */
@Composable
fun ScanDiagnosticsDialog(
    sourceLanguage: String,
    targetLanguage: String,
    translationMode: String,
    enabledPackages: Set<String>,
    onCopied: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val crashPrefs = remember {
        context.getSharedPreferences(TranslatorApplication.CRASH_PREFS, Context.MODE_PRIVATE)
    }
    val diagReport = remember(sourceLanguage, targetLanguage, translationMode, enabledPackages) {
        buildDiagnosticsReport(
            crashPrefs = crashPrefs,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationMode = translationMode,
            enabledPackages = enabledPackages
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan Diagnostics") },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = diagReport,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OverChat Diagnostics", diagReport))
                onCopied()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun buildDiagnosticsReport(
    crashPrefs: android.content.SharedPreferences,
    sourceLanguage: String,
    targetLanguage: String,
    translationMode: String,
    enabledPackages: Set<String>
): String {
    val messengerOn = enabledPackages.contains("com.facebook.orca")
    val trace = crashPrefs.getString(TranslatorApplication.KEY_LAST_CRASH, null)
    val lastCrashSummary = if (trace.isNullOrBlank()) {
        null
    } else {
        val lines = trace.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val headline = lines.firstOrNull()?.take(120) ?: "unknown"
        val appFrame = lines.firstOrNull { it.contains("com.example.translator.") }?.removePrefix("at ")?.take(120)
        val crashAt = crashPrefs.getLong(TranslatorApplication.KEY_LAST_CRASH_AT, 0L)
        val ageSec = if (crashAt > 0L) ((System.currentTimeMillis() - crashAt) / 1000L).coerceAtLeast(0L) else -1L
        buildString {
            append(headline)
            if (appFrame != null) append(" @ $appFrame")
            if (ageSec >= 0L) append(" (${ageSec}s ago)")
        }
    }

    return buildString {
        appendLine("Version: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("AccService: ${ServiceBridge.isAccessibilityActive} | Translating: ${ServiceBridge.isTranslatingActive}")
        appendLine("Pair: $sourceLanguage -> $targetLanguage | Mode: $translationMode")
        appendLine("EnabledPkgs: ${enabledPackages.size} (Messenger=$messengerOn)")
        appendLine("Runtime: ${ServiceBridge.lastRuntimeTrace}")
        appendLine("LastScan: ${ServiceBridge.lastScanDebug}")
        appendLine("LastTree: ${ServiceBridge.lastTreeScanDebug}")
        appendLine("LastProbe: ${ServiceBridge.lastProbeDebug}")
        if (ServiceBridge.lastDraftDebug != "none") {
            appendLine("Draft: ${ServiceBridge.lastDraftDebug}")
        }
        if (ServiceBridge.lastPerAppScanDebug.isNotBlank()) {
            appendLine("AppHistory: ${ServiceBridge.lastPerAppScanDebug}")
        }
        if (!lastCrashSummary.isNullOrBlank()) {
            appendLine("LastCrash: $lastCrashSummary")
        }
    }.trim()
}

