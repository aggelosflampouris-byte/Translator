package com.example.translator.ui.apps

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.translator.AppDefaults
import com.example.translator.ChatAppProfile
import com.example.translator.TargetAppCatalog

private data class CuratedAppUiItem(
    val profile: ChatAppProfile,
    val installedPackage: String?,
    val isEnabled: Boolean
)

/**
 * Pantalla de administración de aplicaciones compatibles con OverChat.
 *
 * Por qué: Permite al usuario habilitar o deshabilitar únicamente las aplicaciones de mensajería
 * verificadas (WhatsApp, Instagram y Viber), manteniendo bloqueadas apps sensibles del sistema.
 */
@Composable
fun TargetAppsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE) }
    var enabledPackages by remember {
        mutableStateOf(TargetAppCatalog.getEnabledPackages(prefs, context.packageName))
    }

    fun updateEnabledPackages(updated: Set<String>) {
        TargetAppCatalog.saveEnabledPackages(prefs, updated, context.packageName)
        enabledPackages = TargetAppCatalog.getEnabledPackages(prefs, context.packageName)
    }

    val curatedItems = remember(enabledPackages) {
        val pm = context.packageManager
        TargetAppCatalog.CURATED_PROFILES.map { profile ->
            val installedPkg = profile.packageNames.firstOrNull { isPackageInstalled(pm, it) }
            val anyEnabled = profile.packageNames.any { enabledPackages.contains(it) }
            CuratedAppUiItem(
                profile = profile,
                installedPackage = installedPkg,
                isEnabled = anyEnabled
            )
        }.sortedByDescending { it.installedPackage != null }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onBack) {
                        Text(text = "← Back")
                    }
                    Text(
                        text = "Supported Apps",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = { updateEnabledPackages(TargetAppCatalog.getDefaultEnabledPackages()) }
                    ) {
                        Text(text = "Defaults", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Translations appear only in the verified apps enabled below. System settings, launchers, keyboards, and financial apps are ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                Text(
                    text = "Verified Apps (${curatedItems.count { it.isEnabled }}/${curatedItems.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            items(curatedItems, key = { it.profile.id }) { item ->
                val subtitle = if (item.installedPackage != null) {
                    "Installed · Chat bubbles"
                } else {
                    "Not installed · Chat bubbles"
                }

                TargetAppToggleRow(
                    title = item.profile.displayName,
                    subtitle = subtitle,
                    iconPackage = item.installedPackage,
                    checked = item.isEnabled,
                    isInstalled = item.installedPackage != null,
                    onCheckedChange = { checked ->
                        val next = enabledPackages.toMutableSet()
                        if (checked) {
                            next.addAll(item.profile.packageNames)
                        } else {
                            next.removeAll(item.profile.packageNames)
                        }
                        updateEnabledPackages(next)
                    }
                )
            }
        }
    }
}

@Composable
private fun TargetAppToggleRow(
    title: String,
    subtitle: String,
    iconPackage: String?,
    checked: Boolean,
    isInstalled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val iconBitmap = remember(iconPackage) {
        if (iconPackage != null) loadAppIconBitmap(context.packageManager, iconPackage) else null
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isInstalled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = title,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.take(1).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
    return try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }
}

private fun loadAppIconBitmap(pm: PackageManager, pkg: String): ImageBitmap? {
    return try {
        pm.getApplicationIcon(pkg).toBitmap(width = 72, height = 72).asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

