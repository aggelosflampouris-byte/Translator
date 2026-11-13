package com.example.translator.ui.main

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.translator.ServiceBridge

/**
 * Tarjeta compacta de estado y concesión de permisos del sistema requeridos por OverChat.
 */
@Composable
fun SystemPermissionsCard(
    canDrawOverlays: Boolean,
    isAccessibilityEnabled: Boolean,
    isNotificationPermissionGranted: Boolean,
    allPermissionsGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppInfo: () -> Unit
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "System Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (allPermissionsGranted) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "· All active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (allPermissionsGranted) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "✓ Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (!allPermissionsGranted) {
                Spacer(modifier = Modifier.height(10.dp))
                PermissionStatusRow(
                    label = "Display over other apps",
                    granted = canDrawOverlays,
                    onFix = onRequestOverlayPermission
                )
                Spacer(modifier = Modifier.height(8.dp))
                PermissionStatusRow(
                    label = "Accessibility Service",
                    granted = isAccessibilityEnabled,
                    onFix = onRequestAccessibilityPermission
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionStatusRow(
                        label = "Notifications",
                        granted = isNotificationPermissionGranted,
                        onFix = onRequestNotificationPermission
                    )
                }

                if (!isAccessibilityEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Android 13+ restricted? App Info → ⋮ → Allow restricted settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenAppInfo) {
                            Text("App Info", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = if (granted) "Active" else "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!granted) {
            TextButton(
                onClick = onFix,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Enable", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Verifica coincidencia exacta de componente (`pkg/class` o forma abreviada `pkg/.SimpleClass`)
 * dentro de la cadena `ENABLED_ACCESSIBILITY_SERVICES` para evitar falsos positivos por prefijo.
 */
internal fun isAccessibilityComponentEnabled(
    enabledServicesSetting: String?,
    packageName: String,
    serviceClassName: String
): Boolean {
    if (enabledServicesSetting.isNullOrBlank()) return false
    for (rawComponent in enabledServicesSetting.split(':')) {
        val component = rawComponent.trim()
        val slashIdx = component.indexOf('/')
        if (slashIdx <= 0 || slashIdx >= component.lastIndex) continue
        val componentPkg = component.substring(0, slashIdx)
        val rawCls = component.substring(slashIdx + 1)
        val resolvedCls = if (rawCls.startsWith(".")) componentPkg + rawCls else rawCls
        if (componentPkg == packageName && resolvedCls == serviceClassName) {
            return true
        }
    }
    return false
}

/**
 * Comprueba de forma resiliente si el servicio de accesibilidad de OverChat está habilitado en el sistema.
 */
fun checkAccessibilityEnabled(context: Context): Boolean {
    if (ServiceBridge.isAccessibilityActive) {
        return true
    }

    val serviceClassName = "com.example.translator.TranslationAccessibilityService"

    // Por qué: Leer Settings.Secure primero evita falsos negativos en capas OEM como One UI/MIUI.
    val settingValue = Settings.Secure.getString(
        context.applicationContext.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    if (isAccessibilityComponentEnabled(settingValue, context.packageName, serviceClassName)) {
        return true
    }

    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == serviceClassName
    }
}

