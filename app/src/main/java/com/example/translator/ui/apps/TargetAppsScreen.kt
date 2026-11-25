package com.example.translator.ui.apps

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.translator.AppDefaults
import com.example.translator.ChatAppProfile
import com.example.translator.R
import com.example.translator.TargetAppCatalog
import com.example.translator.ui.components.OverChatSectionCard
import com.example.translator.ui.components.OverChatSectionLabel
import com.example.translator.ui.components.OverChatTopBar

private data class CuratedAppUiItem(
    val profile: ChatAppProfile,
    val installedPackage: String?,
    val isEnabled: Boolean
)

/**
 * Pantalla de administración de aplicaciones compatibles con OverChat agrupadas en una tarjeta limpia.
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
            OverChatTopBar(
                title = stringResource(R.string.title_supported_apps),
                onBack = onBack,
                trailing = {
                    TextButton(
                        onClick = { updateEnabledPackages(TargetAppCatalog.getDefaultEnabledPackages()) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(text = stringResource(R.string.action_enable_all), style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.apps_screen_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OverChatSectionLabel(
                text = stringResource(
                    R.string.apps_verified_count,
                    curatedItems.count { it.isEnabled },
                    curatedItems.size
                )
            )

            OverChatSectionCard {
                curatedItems.forEachIndexed { index, item ->
                    val subtitle = stringResource(
                        if (item.installedPackage != null) {
                            R.string.apps_status_installed
                        } else {
                            R.string.apps_status_not_installed
                        }
                    )
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
                    if (index < curatedItems.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isInstalled) 1f else 0.6f),
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
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Spacer(modifier = Modifier.height(1.dp))
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
        pm.getApplicationIcon(pkg).toBitmap(width = 76, height = 76).asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
