package com.example.translator

/**
 * Política centralizada de temporización adaptativa para escaneos, desplazamiento, borrador y vigilante.
 *
 * Por qué: Reunir todos los umbrales de rebote en un componente puro facilita la calibración de
 * latencia vs. batería sin duplicar constantes entre el servicio, el coordinador y el teclado.
 */
object ScanTimingPolicy {

    const val QUIET_CONTENT_DEBOUNCE_MS = 160L
    const val BURST_CONTENT_DEBOUNCE_MS = 300L
    const val CONTENT_IDLE_THRESHOLD_MS = 500L
    const val MAX_CONTENT_STARVATION_MS = 600L
    const val SINGLE_FLIGHT_THROTTLE_MS = 90L

    const val SCROLL_SETTLE_DELAY_MS = 220L
    const val SCROLL_QUIET_THRESHOLD_MS = 240L
    const val SCROLL_FORCE_CLEAR_MS = 400L

    const val DRAFT_FAST_PATH_DELAY_MS = 35L
    const val DRAFT_ADAPTIVE_DEBOUNCE_MS = 135L
    const val DRAFT_MAX_STARVATION_MS = 360L
    const val DRAFT_MIN_COALESCE_MS = 55L

    const val WATCHDOG_OVERLAY_INTERVAL_MS = 350L
    const val WATCHDOG_EXIT_VERIFY_DELAY_MS = 450L

    /**
     * Selecciona un retraso breve cuando la conversación estaba en reposo para mostrar mensajes
     * entrantes con menor latencia, ampliándolo bajo ráfagas continuas de indicadores de escritura.
     */
    fun chooseContentDebounceMs(nowMs: Long, lastContentEventMs: Long): Long {
        if (lastContentEventMs <= 0L) return QUIET_CONTENT_DEBOUNCE_MS
        val quietElapsed = (nowMs - lastContentEventMs).coerceAtLeast(0L)
        return if (quietElapsed >= CONTENT_IDLE_THRESHOLD_MS) {
            QUIET_CONTENT_DEBOUNCE_MS
        } else {
            BURST_CONTENT_DEBOUNCE_MS
        }
    }

    /**
     * Calcula la espera para el borrador del teclado priorizando el renderizado casi instantáneo
     * cuando ya existe coincidencia en caché, memoria aprendida o diccionario idiomático.
     */
    fun chooseDraftDelayMs(elapsedTypingMs: Long, hasFastHit: Boolean): Long {
        if (hasFastHit) {
            return DRAFT_FAST_PATH_DELAY_MS
        }
        val remainingStarvation = (DRAFT_MAX_STARVATION_MS - elapsedTypingMs)
            .coerceAtLeast(DRAFT_MIN_COALESCE_MS)
        return minOf(DRAFT_ADAPTIVE_DEBOUNCE_MS, remainingStarvation)
    }
}

