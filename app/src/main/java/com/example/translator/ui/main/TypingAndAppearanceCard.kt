package com.example.translator.ui.main

import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
 * Tarjeta principal de controles rápidos, apps compatibles y diccionario personal unificado.
 *
 * Por qué: Reemplaza las dos tarjetas anteriores de escritura/aprendizaje/tema para dejar en portada
 * solo acciones de uso diario con navegación directa hacia los submenús.
 */
@Composable
fun ControlsAndAppsCard(
    controlInterface: String,
    enabledPackages: Set<String>,
    learningStats: Pair<Int, Int>,
    glossaryEnabled: Boolean,
    learningEnabled: Boolean,
    settingsEnabled: Boolean,
    onControlInterfaceChanged: (String) -> Unit,
    onRequestAddQuickTile: () -> Unit,
    onManageApps: () -> Unit,
    onOpenDictionary: () -> Unit
) {
    val activeCuratedNames = remember(enabledPackages) {
        TargetAppCatalog.CURATED_PROFILES
            .filter { profile -> profile.packageNames.any { it in enabledPackages } }
            .map { it.displayName }
    }
    val enabledAppsSummary = remember(activeCuratedNames) {
        if (activeCuratedNames.isEmpty()) {
            "No apps selected — tap to choose"
        } else {
            activeCuratedNames.joinToString(", ")
        }
    }
    val dictionarySummary = remember(learningStats, glossaryEnabled, learningEnabled) {
        val totalSaved = learningStats.first + learningStats.second
        val countText = if (totalSaved == 0) {
            "No saved fixes yet"
        } else {
            val wordLabel = if (learningStats.first == 1) "1 word" else "${learningStats.first} words"
            val phraseLabel = if (learningStats.second == 1) "1 phrase" else "${learningStats.second} phrases"
            "$wordLabel · $phraseLabel"
        }
        val stateSuffix = when {
            glossaryEnabled && learningEnabled -> ""
            !glossaryEnabled && !learningEnabled -> " · Off"
            glossaryEnabled -> " · Manual only"
            else -> " · Learning only"
        }
        "$countText$stateSuffix"
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Controls",
                style = MaterialTheme.typography.titleSmall,
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
                            text = "Floating",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = controlInterface == AppDefaults.INTERFACE_TILE,
                    onClick = { onControlInterfaceChanged(AppDefaults.INTERFACE_TILE) },
                    enabled = settingsEnabled,
                    label = {
                        Text(
                            text = "Quick Tile",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            ControlHintRow(
                controlInterface = controlInterface,
                settingsEnabled = settingsEnabled,
                onRequestAddQuickTile = onRequestAddQuickTile
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            NavigationSummaryRow(
                title = "Supported Apps",
                subtitle = enabledAppsSummary,
                enabled = settingsEnabled,
                onClick = onManageApps
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            NavigationSummaryRow(
                title = "Personal Dictionary",
                subtitle = dictionarySummary,
                enabled = settingsEnabled,
                onClick = onOpenDictionary
            )
        }
    }
}

@Composable
private fun ControlHintRow(
    controlInterface: String,
    settingsEnabled: Boolean,
    onRequestAddQuickTile: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val hint = when (controlInterface) {
            AppDefaults.INTERFACE_NOTIFICATION -> "Pause, resume, or scan from your notification shade."
            AppDefaults.INTERFACE_BUBBLE -> "Tap floating sphere to pause or resume. Hold for options."
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Toggle OverChat from Quick Settings."
            } else {
                "Swipe down twice, tap Edit, and drag OverChat into Quick Settings."
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (controlInterface == AppDefaults.INTERFACE_TILE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRequestAddQuickTile,
                enabled = settingsEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Add Tile", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun NavigationSummaryRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tarjeta reutilizable de tema visual de las burbujas traducidas (mostrada en Ajustes).
 */
@Composable
fun BubbleThemeCard(
    overlayTheme: String,
    settingsEnabled: Boolean,
    onOverlayThemeChanged: (String) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Overlay Bubble Theme",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Matches dark mode automatically or locks a fixed bubble style.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
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
