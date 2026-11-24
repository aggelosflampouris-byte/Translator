package com.example.translator.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.translator.AppDefaults
import com.example.translator.R
import com.example.translator.TargetAppCatalog
import com.example.translator.ui.components.OverChatSectionCard
import com.example.translator.ui.components.OverChatSectionLabel
import com.example.translator.ui.components.OverChatSegmentedControl

/**
 * Tarjeta principal de aplicaciones compatibles y diccionario personal unificado.
 */
@Composable
fun ControlsAndAppsCard(
    enabledPackages: Set<String>,
    learningStats: Pair<Int, Int>,
    glossaryEnabled: Boolean,
    learningEnabled: Boolean,
    settingsEnabled: Boolean,
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverChatSectionLabel(text = "Apps & Dictionary")

        OverChatSectionCard {
            NavigationSummaryRow(
                title = "Supported Apps",
                subtitle = enabledAppsSummary,
                enabled = settingsEnabled,
                onClick = onManageApps
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
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
private fun NavigationSummaryRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
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
        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Tarjeta de apariencia visual con previsualización en vivo de burbujas según el tema seleccionado.
 */
@Composable
fun BubbleThemeCard(
    overlayTheme: String,
    settingsEnabled: Boolean,
    onOverlayThemeChanged: (String) -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val useLightPreview = overlayTheme == AppDefaults.OVERLAY_THEME_LIGHT ||
        (overlayTheme == AppDefaults.OVERLAY_THEME_AUTO && !isSystemDark)

    val previewBackdrop = if (useLightPreview) Color(0xFFEFEAE2) else Color(0xFF0B141A)
    val incomingBg = if (useLightPreview) Color(0xFFF8FAFC) else Color(0xFF202C33)
    val incomingBorder = if (useLightPreview) Color(0xFFCBD5E1) else Color(0xFF2A3942)
    val quotedBg = if (useLightPreview) Color(0xFFE2E8F0) else Color(0xFF172128)
    val outgoingBg = if (useLightPreview) Color(0xFFE0F2FE) else Color(0xFF005C4B)
    val outgoingBorder = if (useLightPreview) Color(0xFFBAE6FD) else Color(0xFF025144)
    val bubbleInk = if (useLightPreview) Color(0xFF0F172A) else Color(0xFFE9EDEF)
    val quotedInk = if (useLightPreview) Color(0xFF334155) else Color(0xFFD1D7DB)

    OverChatSectionCard {
        Text(
            text = "Overlay Bubble Style",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "Matches dark mode automatically or locks a fixed bubble palette.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = previewBackdrop,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 5.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                    color = incomingBg,
                    border = BorderStroke(0.75.dp, incomingBorder)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = quotedBg
                        ) {
                            Text(
                                text = "Do we meet at 6?",
                                style = MaterialTheme.typography.labelSmall,
                                color = quotedInk,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = "Yes, see you outside the library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = bubbleInk
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 5.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                        color = outgoingBg,
                        border = BorderStroke(0.75.dp, outgoingBorder)
                    ) {
                        Text(
                            text = "Perfect, on my way now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = bubbleInk,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OverChatSegmentedControl(
            options = listOf(
                AppDefaults.OVERLAY_THEME_AUTO to "Auto",
                AppDefaults.OVERLAY_THEME_LIGHT to "Light",
                AppDefaults.OVERLAY_THEME_WHATSAPP to "Classic"
            ),
            selectedValue = overlayTheme,
            enabled = settingsEnabled,
            onSelected = onOverlayThemeChanged
        )
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
