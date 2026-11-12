package com.example.translator

/**
 * Representa las coordenadas en pantalla de una ventana sin acoplarse a `android.graphics.Rect`.
 * Por qué: Permite validar la lógica de selección de ventana en primer plano con pruebas JVM puras.
 */
data class WindowRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Instantánea ligera de una ventana `TYPE_APPLICATION` observada por el servicio de accesibilidad.
 */
data class AppWindowSnapshot(
    val packageName: String?,
    val isActive: Boolean,
    val isFocused: Boolean,
    val layer: Int,
    val bounds: WindowRect? = null
)

/**
 * Resultado de la resolución de ventana en primer plano junto con la razón diagnóstica sin PII.
 */
data class ForegroundResolutionResult(
    val targetPackage: String?,
    val reason: String
)

/**
 * Resuelve de forma determinista qué aplicación habilitada ocupa el primer plano,
 * evitando bloqueos por paneles laterales OEM, superposiciones transitorias o teclados.
 */
object ForegroundTargetResolver {

    const val RECENT_EVENT_GRACE_MS = 5_000L
    // Por qué: Mientras una ventana fullscreen sin root atribuido (p. ej. Messenger en One UI) sigue
    // visible sin cambios ni scroll, la lectura pasiva puede superar 5s sin emitir nuevos eventos.
    const val UNATTRIBUTED_WINDOW_GRACE_MS = 120_000L

    /**
     * Identifica superficies del sistema o teclado que flotan sobre la app de chat sin reemplazarla.
     */
    fun isSystemTransientPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == "com.android.systemui") return true
        return TargetAppCatalog.isKeyboardPackage(pkg)
    }

    /**
     * Comprueba si una ventana ocupa una porción sustancial de la pantalla (al menos 1/3 de ancho
     * y 1/4 de alto) para distinguir chats reales de tiras Edge o ventanas auxiliares 0x0.
     */
    fun isSubstantialWindow(bounds: WindowRect?, screenWidthPx: Int, screenHeightPx: Int): Boolean {
        if (bounds == null) return true
        if (bounds.isEmpty) return false
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return true
        return bounds.width >= screenWidthPx / 3 && bounds.height >= screenHeightPx / 4
    }

    fun resolve(
        activeWindowPkg: String?,
        activeWindowBounds: WindowRect?,
        appWindows: List<AppWindowSnapshot>,
        screenWidthPx: Int,
        screenHeightPx: Int,
        recentEventPkg: String?,
        recentEventAgeMs: Long,
        enabledTargetPackages: Set<String>,
        selfPackage: String
    ): ForegroundResolutionResult {
        fun isEnabledTarget(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            return enabledTargetPackages.contains(pkg) &&
                !TargetAppCatalog.isBlockedSensitivePackage(pkg, selfPackage)
        }

        val substantialWindows = appWindows
            .asSequence()
            .filter { win ->
                val pkg = win.packageName
                (pkg.isNullOrBlank() || !isSystemTransientPackage(pkg)) &&
                    isSubstantialWindow(win.bounds, screenWidthPx, screenHeightPx)
            }
            .sortedWith(
                // Por qué: Ordena primero por capa visual descendente (lo que realmente cubre la pantalla)
                // para evitar que una ventana lanzadora en capa 0 marcada AF bloquee apps visibles encima.
                compareByDescending<AppWindowSnapshot> { it.layer }
                    .thenByDescending { it.isActive || it.isFocused }
            )
            .toList()

        val topSubstantialWin = substantialWindows.firstOrNull()

        // 1. Comprobar primero rootInActiveWindow si apunta a una superficie clara
        if (!activeWindowPkg.isNullOrBlank()) {
            if (isEnabledTarget(activeWindowPkg)) {
                return ForegroundResolutionResult(activeWindowPkg, "active=$activeWindowPkg")
            }
            val substantial = isSubstantialWindow(activeWindowBounds, screenWidthPx, screenHeightPx)
            if (activeWindowPkg == selfPackage) {
                // Por qué: La pantalla principal propia (sustancial) debe pausar traducciones;
                // una burbuja o superposición pequeña permite inspeccionar el chat subyacente.
                if (substantial) {
                    return ForegroundResolutionResult(null, "blocked_self_substantial")
                }
            } else if (!isSystemTransientPackage(activeWindowPkg)) {
                if (TargetAppCatalog.isBlockedSensitivePackage(activeWindowPkg, selfPackage)) {
                    val activeLayer = appWindows
                        .filter { it.packageName == activeWindowPkg }
                        .maxOfOrNull { it.layer }
                    if (topSubstantialWin != null && (activeLayer == null || topSubstantialWin.layer > activeLayer)) {
                        val topPkg = topSubstantialWin.packageName
                        if (isEnabledTarget(topPkg)) {
                            return ForegroundResolutionResult(topPkg, "win_above_blocked=$topPkg")
                        }
                        // Por qué: En One UI, Messenger puede aparecer como ventana TYPE_APPLICATION en capa superior
                        // sin root atribuido (window.root == null) sobre el lanzador en capa 0.
                        if (topPkg.isNullOrBlank() &&
                            recentEventAgeMs in 0..UNATTRIBUTED_WINDOW_GRACE_MS &&
                            isEnabledTarget(recentEventPkg)
                        ) {
                            return ForegroundResolutionResult(recentEventPkg, "null_win_above_blocked=$recentEventPkg")
                        }
                    }
                    if (activeWindowBounds == null || substantial) {
                        return ForegroundResolutionResult(null, "blocked_active=$activeWindowPkg")
                    }
                } else if (substantial) {
                    return ForegroundResolutionResult(null, "other_active=$activeWindowPkg")
                }
            }
        }

        // 2. Inspeccionar las ventanas TYPE_APPLICATION sustanciales ordenadas por capa
        if (topSubstantialWin != null) {
            val topPkg = topSubstantialWin.packageName
            if (topPkg.isNullOrBlank()) {
                if (recentEventAgeMs in 0..UNATTRIBUTED_WINDOW_GRACE_MS && isEnabledTarget(recentEventPkg)) {
                    return ForegroundResolutionResult(recentEventPkg, "null_top_win=$recentEventPkg")
                }
                return ForegroundResolutionResult(null, "null_win_no_recent")
            }
            if (isEnabledTarget(topPkg)) {
                return ForegroundResolutionResult(topPkg, "win=$topPkg")
            }
            return ForegroundResolutionResult(null, "other_win=$topPkg")
        }

        // 3. Respaldo por evento reciente solo si las APIs de ventana devolvieron vacío o transitorio
        if (recentEventAgeMs in 0..RECENT_EVENT_GRACE_MS && isEnabledTarget(recentEventPkg)) {
            return ForegroundResolutionResult(recentEventPkg, "recent_evt=$recentEventPkg")
        }

        return ForegroundResolutionResult(
            null,
            "none(act=${activeWindowPkg ?: "null"},wins=${appWindows.size},rec=${recentEventPkg ?: "null"})"
        )
    }
}

