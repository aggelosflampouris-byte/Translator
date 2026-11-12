package com.example.translator

/**
 * Puente ligero desacoplado para comunicar el estado entre servicios.
 *
 * Por qué: Anteriormente, [FloatingBubbleService] y [TranslationAccessibilityService]
 * mantenían referencias estáticas a sus propias instancias (`instance`, `currentInstance`),
 * lo que provocaba fugas de memoria al impedir la recolección de basura del Context
 * y generaba un acoplamiento global oculto.
 *
 * [ServiceBridge] almacena únicamente estados primitivos y un callback, evitando retener
 * cualquier referencia a un Service o Context.
 */
object ServiceBridge {

    // ── FloatingBubbleService state ────────────────────────────────────────────

    /** True while [FloatingBubbleService] is alive (between onCreate and onDestroy). */
    @Volatile var isBubbleRunning: Boolean = false

    /** True when the user has tapped the sphere to activate translation. */
    @Volatile var isTranslatingActive: Boolean = false

    // ── TranslationAccessibilityService state ──────────────────────────────────

    /** True while [TranslationAccessibilityService] is bound and connected. */
    @Volatile var isAccessibilityActive: Boolean = false

    /**
     * Callback registered by [TranslationAccessibilityService] in [onServiceConnected].
     * Invoked by [FloatingBubbleService] when the user toggles translation on/off.
     * Cleared in [onUnbind] / [onDestroy] — never holds a Service reference after unbind.
     */
    @Volatile var onTranslationStateChanged: ((Boolean) -> Unit)? = null

    // ── System Notification Menu & Tile state ───────────────────────────────────

    /** True while the persistent system notification menu is posted in the notification shade. */
    @Volatile var isNotificationActive: Boolean = false

    /**
     * Callback to trigger an immediate scan and translation of the visible WhatsApp chat.
     * Invoked when the user taps "Translate Screen" in the notification menu or Quick Settings tile.
     */
    @Volatile var onScanRequested: (() -> Unit)? = null

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

    fun isAnyServiceActive(): Boolean {
        return isTranslatingActive || isBubbleRunning || isNotificationActive
    }

    fun notifyUiStateChanged() {
        onUiStateChanged?.invoke()
    }
}

