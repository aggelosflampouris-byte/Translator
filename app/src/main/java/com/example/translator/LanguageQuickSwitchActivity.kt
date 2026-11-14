package com.example.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.translator.theme.TranslatorTheme
import com.example.translator.ui.main.LanguageDropdown

/**
 * Ventana emergente ligera lanzada desde la notificación para cambiar el par de idiomas sin salir del chat.
 *
 * Por qué: Usa un PendingIntent de Activity directo con tema translúcido para evitar bloqueos de
 * trampolín en Android 12+, colapsar la barra de notificaciones automáticamente y no requerir `SYSTEM_ALERT_WINDOW`.
 */
class LanguageQuickSwitchActivity : ComponentActivity() {

    private lateinit var preferencesRepository: TranslatorPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        preferencesRepository = TranslatorPreferencesRepository(applicationContext)

        setContent {
            val prefs by preferencesRepository.preferences.collectAsStateWithLifecycle()

            TranslatorTheme {
                QuickLanguageSwitchDialogCard(
                    sourceLanguage = prefs.sourceLanguage,
                    targetLanguage = prefs.targetLanguage,
                    onSourceSelected = { selected ->
                        preferencesRepository.setSourceLanguage(selected)
                        syncUpdatedLanguagePair()
                    },
                    onTargetSelected = { selected ->
                        preferencesRepository.setTargetLanguage(selected)
                        syncUpdatedLanguagePair()
                    },
                    onSwapLanguages = {
                        preferencesRepository.swapLanguages()
                        syncUpdatedLanguagePair()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        if (::preferencesRepository.isInitialized) {
            preferencesRepository.close()
        }
        super.onDestroy()
    }

    private fun syncUpdatedLanguagePair() {
        val updated = preferencesRepository.preferences.value
        TranslationPipeline.ensureModelsDownloaded(
            source = updated.sourceLanguage,
            target = updated.targetLanguage,
            onSuccess = {},
            onError = {}
        )
        if (ServiceBridge.isNotificationActive) {
            TranslationNotificationManager.showNotification(
                context = applicationContext,
                isTranslating = ServiceBridge.isTranslatingActive
            )
        }
    }
}

@Composable
private fun QuickLanguageSwitchDialogCard(
    sourceLanguage: String,
    targetLanguage: String,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onSwapLanguages: () -> Unit,
    onDismiss: () -> Unit
) {
    val outsideInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = outsideInteraction,
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.quick_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.quick_switch_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LanguageDropdown(
                            label = "From",
                            selectedCode = sourceLanguage,
                            options = AppDefaults.AVAILABLE_LANGUAGES,
                            onSelected = onSourceSelected
                        )
                    }

                    IconButton(
                        onClick = onSwapLanguages,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            text = "⇄",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        LanguageDropdown(
                            label = "To",
                            selectedCode = targetLanguage,
                            options = AppDefaults.AVAILABLE_LANGUAGES,
                            onSelected = onTargetSelected
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = onSwapLanguages
                    ) {
                        Text("⇄ Swap")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = onDismiss
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
