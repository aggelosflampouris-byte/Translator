package com.example.translator

/**
 * Pure decision logic for the crash handler.
 *
 * Por qué: Relanzar la app tras cada fallo provoca un bucle infinito de reinicios si el fallo
 * ocurre durante el arranque. Esta política limita los relanzamientos a fallos aislados en el tiempo.
 */
object CrashLoopPolicy {

    // Por qué: Fallos separados por más de este intervalo se consideran independientes entre sí.
    const val CRASH_WINDOW_MS = 60_000L

    // Por qué: Solo se permite un relanzamiento automático por ventana; el segundo fallo indica un bucle.
    const val MAX_RELAUNCHES_PER_WINDOW = 1

    data class Decision(val newCrashCount: Int, val shouldRelaunch: Boolean)

    /**
     * @param previousCount Consecutive crashes recorded within the current window.
     * @param lastCrashAtMillis Wall-clock time of the previous crash, or 0 if none.
     * @param nowMillis Current wall-clock time.
     */
    fun evaluate(previousCount: Int, lastCrashAtMillis: Long, nowMillis: Long): Decision {
        val elapsed = nowMillis - lastCrashAtMillis
        val isWithinWindow = lastCrashAtMillis > 0L && elapsed in 0..CRASH_WINDOW_MS
        val newCount = if (isWithinWindow) previousCount + 1 else 1
        return Decision(
            newCrashCount = newCount,
            shouldRelaunch = newCount <= MAX_RELAUNCHES_PER_WINDOW
        )
    }
}

