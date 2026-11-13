package com.example.translator.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.translator.AppDefaults
import com.example.translator.TargetAppCatalog

/**
 * Tarjeta de opciones de escritura, aprendizaje local e interfaz de control flotante o en notificación.
 */
@Composable
fun TypingAndControlsCard(
    keyboardTranslatorEnabled: Boolean,
    learningEnabled: Boolean,
    learningStats: Pair<Int, Int>,
    controlInterface: String,
    settingsEnabled: Boolean,
    onKeyboardTranslatorChanged: (Boolean) -> Unit,
    onLearningChanged: (Boolean) -> Unit,
    onRequestResetLearning: () -> Unit,
    onControlInterfaceChanged: (String) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Typing & Controls",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keyboard Draft Translator",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Translates outgoing messages in real-time as you type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Learn From My Edits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${learningStats.first} learned words · ${learningStats.second} phrases",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (learningStats.first + learningStats.second > 0) {
                    TextButton(
                        onClick = onRequestResetLearning,
                        enabled = settingsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (settingsEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Switch(
                    checked = learningEnabled,
                    enabled = settingsEnabled,
                    onCheckedChange = onLearningChanged
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                text = "Control Interface",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = controlInterface == AppDefaults.INTERFACE_NOTIFICATION,
                    onClick = { onControlInterfaceChanged(AppDefaults.INTERFACE_NOTIFICATION) },
                    enabled = settingsEnabled,
                    label = {
                        Text(
                            text = "Notification",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = controlInterface == AppDefaults.INTERFACE_BUBBLE,
                    onClick = { onControlInterfaceChanged(AppDefaults.INTERFACE_BUBBLE) },
                    enabled = settingsEnabled,
                    label = {
                        Text(
                            text = "Floating Bubble",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Diálogo de confirmación para borrar las correcciones y frases aprendidas localmente.
 */
@Composable
fun ResetLearningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset learned data?") },
        text = { Text("All learned words and remembered phrases will be deleted from this phone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Tarjeta de selección de aplicaciones compatibles y tema visual de las burbujas traducidas.
 */
@Composable
fun SupportedAppsAndThemeCard(
    enabledPackages: Set<String>,
    overlayTheme: String,
    settingsEnabled: Boolean,
    onManageApps: () -> Unit,
    onOverlayThemeChanged: (String) -> Unit
) {
    val activeCuratedNames = remember(enabledPackages) {
        TargetAppCatalog.CURATED_PROFILES
            .filter { profile -> profile.packageNames.any { it in enabledPackages } }
            .map { it.displayName }
    }
    val enabledAppsSummary = remember(enabledPackages, activeCuratedNames) {
        if (enabledPackages.isEmpty() || activeCuratedNames.isEmpty()) {
            "No apps selected — tap Manage to enable chat apps."
        } else {
            "${activeCuratedNames.joinToString(", ")} (${enabledPackages.size} pkgs)"
        }
    }

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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "Supported Apps & Theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = enabledAppsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onManageApps,
                    enabled = settingsEnabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Manage")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Overlay Bubble Theme",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val themeOptions = listOf(
                    AppDefaults.OVERLAY_THEME_AUTO to "Auto",
                    AppDefaults.OVERLAY_THEME_LIGHT to "Light",
                    AppDefaults.OVERLAY_THEME_WHATSAPP to "Classic"
                )
                themeOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = overlayTheme == value,
                        onClick = { onOverlayThemeChanged(value) },
                        enabled = settingsEnabled,
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

