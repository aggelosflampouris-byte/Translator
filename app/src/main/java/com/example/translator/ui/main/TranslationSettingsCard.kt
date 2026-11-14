package com.example.translator.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.translator.AppDefaults

/**
 * Tarjeta principal de configuración de idiomas, modo de activación y traductor de borrador al escribir.
 */
@Composable
fun TranslationSettingsCard(
    sourceLanguage: String,
    targetLanguage: String,
    availableLanguages: List<String>,
    modelStatus: String,
    translationMode: String,
    keyboardTranslatorEnabled: Boolean,
    settingsEnabled: Boolean,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onSwapLanguages: () -> Unit,
    onTranslationModeChanged: (String) -> Unit,
    onKeyboardTranslatorChanged: (Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Languages & Translation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isReady = modelStatus.contains("ready", ignoreCase = true)
                    Surface(
                        modifier = Modifier.size(7.dp),
                        shape = CircleShape,
                        color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    ) {}
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isReady) "Offline ready" else modelStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Por qué: Restringe el par lingüístico a los modelos bidireccionales soportados offline.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LanguageDropdown("From", sourceLanguage, availableLanguages, enabled = settingsEnabled, onSelected = onSourceSelected)
                }

                IconButton(
                    onClick = onSwapLanguages,
                    enabled = settingsEnabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(
                        text = "⇄",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (settingsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    LanguageDropdown("To", targetLanguage, availableLanguages, enabled = settingsEnabled, onSelected = onTargetSelected)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                text = "How to translate",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = translationMode == AppDefaults.MODE_ALL,
                    onClick = { onTranslationModeChanged(AppDefaults.MODE_ALL) },
                    enabled = settingsEnabled,
                    label = {
                        Text(
                            text = "Auto-Translate",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = translationMode == AppDefaults.MODE_TAP,
                    onClick = { onTranslationModeChanged(AppDefaults.MODE_TAP) },
                    enabled = settingsEnabled,
                    label = {
                        Text(
                            text = "Tap Message",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = if (translationMode == AppDefaults.MODE_TAP)
                    "Tap any message in chat to reveal its translation."
                else
                    "Translates visible chat messages automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Por qué: Agrupa la traducción del borrador aquí porque pertenece al comportamiento de traducción
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Translate What I Type",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Preview and insert translations as you type a message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Switch(
                    checked = keyboardTranslatorEnabled,
                    enabled = settingsEnabled,
                    onCheckedChange = onKeyboardTranslatorChanged
                )
            }
        }
    }
}

/**
 * Desplegable de selección de idioma con etiqueta legible en mayúscula inicial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(
    label: String,
    selectedCode: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            expanded = false
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            value = AppDefaults.formatLanguageDisplayName(selectedCode),
            onValueChange = {},
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(AppDefaults.formatLanguageDisplayName(code)) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

