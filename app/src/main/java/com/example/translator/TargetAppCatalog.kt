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
 * Catálogo centralizado de aplicaciones compatibles, perfiles de diseño y lista de bloqueo de seguridad.
 *
 * Por qué: Permite expandir OverChat más allá de WhatsApp manteniendo una lista blanca controlada
 * y evitando leer aplicaciones sensibles (bancos, gestores de contraseñas, ajustes y teclados).
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
            id = "telegram",
            displayName = "Telegram",
            packageNames = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 52,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "messenger",
            displayName = "Messenger",
            packageNames = setOf("com.facebook.orca", "com.facebook.mlite"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 52,
            bottomInsetDp = 44,
            expandContainerToParent = false,
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
        ),
        ChatAppProfile(
            id = "signal",
            displayName = "Signal",
            packageNames = setOf("org.thoughtcrime.securesms"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 48,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "discord",
            displayName = "Discord",
            packageNames = setOf("com.discord"),
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            topInsetDp = 56,
            bottomInsetDp = 48,
            expandContainerToParent = false,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "messages",
            displayName = "Messages (SMS/RCS)",
            packageNames = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 48,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "slack",
            displayName = "Slack",
            packageNames = setOf("com.Slack"),
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            topInsetDp = 56,
            bottomInsetDp = 44,
            expandContainerToParent = false,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "line",
            displayName = "LINE",
            packageNames = setOf("jp.naver.line.android"),
            alignmentMode = ChatAlignmentMode.LEFT_RIGHT_CHAT,
            topInsetDp = 48,
            bottomInsetDp = 40,
            expandContainerToParent = true,
            defaultEnabled = true
        ),
        ChatAppProfile(
            id = "snapchat",
            displayName = "Snapchat",
            packageNames = setOf("com.snapchat.android"),
            alignmentMode = ChatAlignmentMode.LEFT_STREAM_CHAT,
            topInsetDp = 56,
            bottomInsetDp = 48,
            expandContainerToParent = false,
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

    private val BLOCKED_PACKAGE_KEYWORDS = listOf(
        "launcher", "quickstep", "trebuchet", "nexuslauncher",
        "keyboard", "honeyboard", "inputmethod", "latinime", "gboard", "swiftkey",
        "bank", "banking", "wallet", "paypal", "revolut", "venmo", "cashapp",
        "authenticator", "1password", "agilebits", "bitwarden", "keepass", "dashlane", "lastpass",
        "keychain", "credential"
    )

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
     * Bloquea de forma inmutable paquetes del sistema, lanzadores, teclados y apps financieras/contraseñas.
     */
    fun isBlockedSensitivePackage(pkg: String?, selfPackage: String): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == selfPackage) return true
        if (BLOCKED_EXACT_PACKAGES.contains(pkg)) return true
        if (isKeyboardPackage(pkg)) return true

        val lower = pkg.lowercase()
        if (lower.endsWith(".home") || lower.contains(".home.")) return true
        return BLOCKED_PACKAGE_KEYWORDS.any { keyword ->
            // Por qué: "ime" solo se comprueba como segmento para no bloquear paquetes legítimos como "anime" o "time"
            lower.contains(keyword)
        }
    }

    /**
     * Obtiene los paquetes habilitados por el usuario, garantizando siempre el filtrado de paquetes bloqueados.
     */
    fun getEnabledPackages(prefs: SharedPreferences, selfPackage: String): Set<String> {
        val stored = prefs.getStringSet(AppDefaults.KEY_ENABLED_PACKAGES, null)
        val rawSet = stored ?: getDefaultEnabledPackages()
        return rawSet.filter { !isBlockedSensitivePackage(it, selfPackage) }.toSet()
    }

    /**
     * Guarda el conjunto de paquetes habilitados filtrando cualquier paquete sensible bloqueado.
     */
    fun saveEnabledPackages(prefs: SharedPreferences, packages: Set<String>, selfPackage: String) {
        val sanitized = packages.filter { !isBlockedSensitivePackage(it, selfPackage) }.toSet()
        prefs.edit().putStringSet(AppDefaults.KEY_ENABLED_PACKAGES, sanitized).apply()
    }

    /**
     * Resuelve el perfil visual y geométrico para un paquete concreto.
     */
    fun profileForPackage(pkg: String?): ChatAppProfile {
        if (pkg.isNullOrBlank()) return GENERIC_DEFAULT_PROFILE
        return PROFILE_BY_PACKAGE[pkg] ?: GENERIC_DEFAULT_PROFILE
    }

    /**
     * Lista de todos los paquetes conocidos para consultas del sistema (<queries>).
     */
    fun allCuratedPackages(): Set<String> = PROFILE_BY_PACKAGE.keys
}

