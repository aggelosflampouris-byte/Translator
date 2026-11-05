package com.example.translator

import android.graphics.Rect

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

    /**
     * Current bounding rect of the floating sphere in screen coordinates.
     * Updated on the Main thread whenever the sphere moves or is added/removed.
     */
    @Volatile var currentBubbleBounds: Rect? = null

    // ── TranslationAccessibilityService state ──────────────────────────────────

    /** True while [TranslationAccessibilityService] is bound and connected. */
    @Volatile var isAccessibilityActive: Boolean = false

    /**
     * Callback registered by [TranslationAccessibilityService] in [onServiceConnected].
     * Invoked by [FloatingBubbleService] when the user toggles translation on/off.
     * Cleared in [onUnbind] / [onDestroy] — never holds a Service reference after unbind.
     */
    @Volatile var onTranslationStateChanged: ((Boolean) -> Unit)? = null
}

