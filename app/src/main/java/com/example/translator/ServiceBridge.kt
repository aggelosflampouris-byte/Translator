package com.example.translator

import kotlinx.coroutines.flow.StateFlow

/**
 * Puente ligero desacoplado para comunicar el estado entre componentes.
 *
 * Por qué: Respalda los estados de ejecución mediante [TranslatorSessionController] (`StateFlow`)
 * para que Compose y los servicios consuman una única fuente reactiva sin referencias estáticas a `Context`.
 */
object ServiceBridge {

    val sessionController = TranslatorSessionController()
    val runtimeState: StateFlow<TranslatorRuntimeState>
        get() = sessionController.runtimeState

    // ── Floating Sphere & Session state ────────────────────────────────────────

    /** True while the floating sphere overlay is shown. */
    var isBubbleRunning: Boolean
        get() = sessionController.current.isBubbleRunning
        set(value) {
            sessionController.setBubbleRunning(value)
        }

    /** True when translation scanning/typing assistance is active. */
    var isTranslatingActive: Boolean
        get() = sessionController.current.isTranslatingActive
        set(value) {
            sessionController.setTranslatingActive(value)
        }

    // ── TranslationAccessibilityService state ──────────────────────────────────

    /** True while [TranslationAccessibilityService] is bound and connected. */
    var isAccessibilityActive: Boolean
        get() = sessionController.current.isAccessibilityActive
        set(value) {
            sessionController.setAccessibilityActive(value)
        }

    /**
     * Callback registered by [TranslationAccessibilityService] in [onServiceConnected].
     * Invoked when the user toggles translation on/off from Notification, Tile, Sphere or MainScreen.
     */
    @Volatile var onTranslationStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Callback para mostrar u ocultar inmediatamente la esfera flotante (`TYPE_ACCESSIBILITY_OVERLAY`).
     */
    @Volatile var onBubbleVisibilityChanged: ((Boolean) -> Unit)? = null

    // ── System Notification Menu & Tile state ───────────────────────────────────

    /** True while the persistent system notification menu is posted in the notification shade. */
    var isNotificationActive: Boolean
        get() = sessionController.current.isNotificationActive
        set(value) {
            sessionController.setNotificationActive(value)
        }

    /**
     * Callback para actualizar bajo demanda las cadenas diagnósticas cuando el usuario abre el diálogo de diagnóstico.
     */
    @Volatile var onDiagnosticsRefreshRequested: (() -> Unit)? = null

    /**
     * Callback para sincronizar inmediatamente la apariencia de la esfera flotante si está visible.
     * Se limpia al desconectar [TranslationAccessibilityService].
     */
    @Volatile var onBubbleAppearanceSync: ((Boolean) -> Unit)? = null

    /**
     * Callback para vaciar cachés en memoria cuando el usuario restablece los datos aprendidos.
     * Se limpia al desconectar `TranslationAccessibilityService`.
     */
    @Volatile var onLearningDataCleared: (() -> Unit)? = null

    /**
     * Callback para actualizar inmediatamente el estado reactivo de la interfaz principal (Start / Stop).
     */
    @Volatile var onUiStateChanged: (() -> Unit)? = null

    /**
     * Resumen diagnóstico sin texto privado del último escaneo de accesibilidad y superposición.
     * Por qué: Permite diagnosticar en dispositivos físicos por qué una app objetivo no genera burbujas
     * sin registrar ni exponer el contenido de los mensajes del usuario.
     */
    @Volatile var lastScanDebug: String = "No scan executed yet."

    /**
     * Última traza de escaneo que sí obtuvo una raíz de nodos no nula de la app objetivo.
     * Por qué: Evita que abortos transitorios al salir hacia SystemUI/Recents sobrescriban
     * el diagnóstico real del árbol de mensajes.
     */
    @Volatile var lastTreeScanDebug: String = "No non-null root scan yet."

    /**
     * Traza continua del ciclo de vida, eventos y ventanas externas sin contenido de mensajes.
     * Por qué: Permite distinguir si un fallo ocurre por falta de eventos, ventanas bloqueantes o bucle detenido.
     */
    @Volatile var lastRuntimeTrace: String = "No runtime events yet."

    /**
     * Diagnóstico estructural sin texto privado de fuentes de eventos, nodos editables y prueba de flags.
     * Por qué: Permite determinar sin permisos extra si un árbol bloqueado expone jerarquía vía EditText o cambio de flags.
     */
    @Volatile var lastProbeDebug: String = "No probe yet."

    /**
     * Estado compacto del motor inverso del teclado sin incluir texto escrito por el usuario.
     */
    @Volatile var lastDraftDebug: String = "none"

    /**
     * Resumen reciente por aplicación visitada para conservar trazas por app al cambiar de ventana.
     */
    @Volatile var lastPerAppScanDebug: String = ""

    fun isAnyServiceActive(): Boolean {
        return isTranslatingActive || isBubbleRunning || isNotificationActive
    }

    fun notifyUiStateChanged() {
        onUiStateChanged?.invoke()
    }
}

