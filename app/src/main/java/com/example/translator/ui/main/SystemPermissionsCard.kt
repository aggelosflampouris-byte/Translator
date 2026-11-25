package com.example.translator.ui.main

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.translator.R
import com.example.translator.ServiceBridge
import com.example.translator.ui.components.OverChatSectionCard
import com.example.translator.ui.components.OverChatSectionLabel

/**
 * Tarjeta compacta de estado y concesión guiada de permisos requeridos por OverChat.
 */
@Composable
fun SystemPermissionsCard(
    isAccessibilityEnabled: Boolean,
    isNotificationRequired: Boolean,
    isNotificationPermissionGranted: Boolean,
    allPermissionsGranted: Boolean,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverChatSectionLabel(text = stringResource(R.string.section_setup_required))

        OverChatSectionCard(
            borderColor = if (allPermissionsGranted) {
                MaterialTheme.colorScheme.outlineVariant
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.perm_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (allPermissionsGranted) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.status_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (!allPermissionsGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                PermissionStatusRow(
                    stepNumber = "1",
                    label = stringResource(R.string.perm_accessibility_label),
                    granted = isAccessibilityEnabled,
                    onFix = onRequestAccessibilityPermission
                )
                if (isNotificationRequired) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionStatusRow(
                        stepNumber = "2",
                        label = stringResource(R.string.perm_notifications_label),
                        granted = isNotificationPermissionGranted,
                        onFix = onRequestNotificationPermission
                    )
                }

                if (!isAccessibilityEnabled) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.perm_restricted_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onOpenAppInfo,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.perm_app_info), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.perm_privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    stepNumber: String,
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
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (granted) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            text = stepNumber,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(if (granted) R.string.status_active else R.string.status_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!granted) {
            FilledTonalButton(
                onClick = onFix,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.action_enable), style = MaterialTheme.typography.labelMedium)
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

