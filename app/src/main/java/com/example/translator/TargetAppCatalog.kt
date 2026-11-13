package com.example.translator

import android.content.SharedPreferences

/**
 * Modo de alineación visual de los mensajes dentro de la aplicación objetivo.
 */
enum class ChatAlignmentMode {
    LEFT_RIGHT_CHAT,
    LEFT_STREAM_CHAT
}

/**
 * Perfil de diseño y comportamiento por aplicación para adaptar el escaneo y las burbujas superpuestas.
 */
data class ChatAppProfile(
    val id: String,
    val displayName: String,
    val packageNames: Set<String>,
    val alignmentMode: ChatAlignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
    val topInsetDp: Int = 48,
    val bottomInsetDp: Int = 40,
    val expandContainerToParent: Boolean = true,
    val defaultEnabled: Boolean = true
)

/**
 * Catálogo centralizado de aplicaciones compatibles probadas, perfiles de diseño y lista de bloqueo de seguridad.
 *
 * Por qué: Restringe el alcance de accesibilidad únicamente a las aplicaciones verificadas
 * (WhatsApp, Instagram y Viber) y excluye cualquier app sensible del sistema o no probada.
 */
object TargetAppCatalog {

    val CURATED_PROFILES: List<ChatAppProfile> = listOf(
        ChatAppProfile(
            id = "whatsapp",
            displayName = "WhatsApp",
            packageNames = setOf("com.whatsapp", "com.whatsapp.w4b"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 48,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "instagram",
            displayName = "Instagram",
            packageNames = setOf("com.instagram.android"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 56,
            bottomInsetDp = 44,
            expandContainerToParent = false,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "viber",
            displayName = "Viber",
            packageNames = setOf("com.viber.voip"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 48,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        )
    )

    val GENERIC_DEFAULT_PROFILE = ChatAppProfile(
        id = "generic",
        displayName = "Chat App",
        packageNames = emptySet(),
        alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
        topInsetDp = 52,
        bottomInsetDp = 44,
        // Por qué: En aplicaciones desconocidas no se expande al nodo padre para evitar cubrir avatares o botones contiguos
        expandContainerToParent = false,
        defaultEnabled = false
    )

    private val PROFILE_BY_PACKAGE: Map<String, ChatAppProfile> = buildMap {
        for (profile in CURATED_PROFILES) {
            for (pkg in profile.packageNames) {
                put(pkg, profile)
            }
        }
    }

    private val BLOCKED_EXACT_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.vending"
    )

    private val LAUNCHER_PACKAGE_KEYWORDS = listOf(
        "launcher", "quickstep", "trebuchet", "nexuslauncher"
    )

    private val PRIVACY_SENSITIVE_KEYWORDS = listOf(
        "bank", "banking", "wallet", "paypal", "revolut", "venmo", "cashapp",
        "authenticator", "1password", "agilebits", "bitwarden", "keepass", "dashlane", "lastpass",
        "keychain", "credential"
    )

    private val BLOCKED_PACKAGE_KEYWORDS = LAUNCHER_PACKAGE_KEYWORDS + listOf(
        "keyboard", "honeyboard", "inputmethod", "latinime", "gboard", "swiftkey"
    ) + PRIVACY_SENSITIVE_KEYWORDS

    /**
     * Devuelve el conjunto predeterminado de paquetes habilitados para la primera ejecución.
     */
    fun getDefaultEnabledPackages(): Set<String> {
        return CURATED_PROFILES
            .filter { it.defaultEnabled }
            .flatMap { it.packageNames }
            .toSet()
    }

    /**
     * Indica si un paquete corresponde al lanzador del sistema o pantalla de inicio.
     */
    fun isLauncherPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        if (lower.endsWith(".home") || lower.contains(".home.")) return true
        return LAUNCHER_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Indica si un paquete pertenece a un teclado/IME en pantalla.
     */
    fun isKeyboardPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return lower.contains("keyboard") ||
            lower.contains("honeyboard") ||
            lower.contains("inputmethod") ||
            lower.contains(".ime") ||
            lower.contains("latinime") ||
            lower.contains("gboard") ||
            lower.contains("swiftkey")
    }

    /**
     * Indica si un paquete pertenece a apps financieras, de pagos o gestores de contraseñas.
     * Por qué: Evita incluso registrar sus nombres en trazas diagnósticas externas.
     */
    fun isPrivacySensitivePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return PRIVACY_SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Bloquea de forma inmutable paquetes del sistema, lanzadores, teclados y apps financieras/contraseñas.
     */
    fun isBlockedSensitivePackage(pkg: String?, selfPackage: String): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == selfPackage) return true
        if (BLOCKED_EXACT_PACKAGES.contains(pkg)) return true
        if (isKeyboardPackage(pkg)) return true

        val lower = pkg.lowercase()
        if (lower.endsWith(".home") || lower.contains(".home.")) return true
        return BLOCKED_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Indica si el paquete pertenece a la lista blanca de aplicaciones probadas y compatibles.
     */
    fun isSupportedPackage(pkg: String?): Boolean {
        return !pkg.isNullOrBlank() && PROFILE_BY_PACKAGE.containsKey(pkg)
    }

    /**
     * Obtiene los paquetes habilitados por el usuario, garantizando siempre el filtrado de paquetes
     * sensibles y descartando cualquier paquete heredado que ya no forme parte de la lista soportada.
     */
    fun getEnabledPackages(prefs: SharedPreferences, selfPackage: String): Set<String> {
        val stored = prefs.getStringSet(AppDefaults.KEY_ENABLED_PACKAGES, null)
        val rawSet = stored ?: getDefaultEnabledPackages()
        return rawSet.filter { isSupportedPackage(it) && !isBlockedSensitivePackage(it, selfPackage) }.toSet()
    }

    /**
     * Guarda el conjunto de paquetes habilitados filtrando paquetes sensibles y no soportados.
     */
    fun saveEnabledPackages(prefs: SharedPreferences, packages: Set<String>, selfPackage: String) {
        val sanitized = packages.filter { isSupportedPackage(it) && !isBlockedSensitivePackage(it, selfPackage) }.toSet()
        prefs.edit().putStringSet(AppDefaults.KEY_ENABLED_PACKAGES, sanitized).apply()
    }

    /**
     * Resuelve el perfil visual y geométrico para un paquete concreto.
     */
    fun profileForPackage(pkg: String?): ChatAppProfile {
        if (pkg.isNullOrBlank()) return GENERIC_DEFAULT_PROFILE
        return PROFILE_BY_PACKAGE[pkg] ?: GENERIC_DEFAULT_PROFILE
    }
}

