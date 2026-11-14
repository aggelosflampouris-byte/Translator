package com.example.translator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado agregado de cara al usuario para mostrar el estado real en encabezado y botones.
 */
enum class SessionDisplayState {
    OFF,
    PAUSED,
    TRANSLATING
}

/**
 * Snapshot inmutable del estado en tiempo real de OverChat.
 *
 * Por qué: Reemplaza las banderas sueltas y callbacks manuales para que la UI Compose y los servicios
 * observen una sola fuente reactiva de verdad.
 */
data class TranslatorRuntimeState(
    val isAccessibilityActive: Boolean = false,
    val isTranslatingActive: Boolean = false,
    val isBubbleRunning: Boolean = false,
    val isNotificationActive: Boolean = false
) {
    val isAnyServiceActive: Boolean
        get() = isTranslatingActive || isBubbleRunning || isNotificationActive

    // Por qué: Evita que la interfaz reporte "Translating" cuando solo está visible la esfera en pausa
    fun displayState(): SessionDisplayState = when {
        isTranslatingActive -> SessionDisplayState.TRANSLATING
        isBubbleRunning || isNotificationActive -> SessionDisplayState.PAUSED
        else -> SessionDisplayState.OFF
    }
}

/**
 * Controlador thread-safe del ciclo de sesión respaldado por [StateFlow].
 */
class TranslatorSessionController {
    private val _runtimeState = MutableStateFlow(TranslatorRuntimeState())
    val runtimeState: StateFlow<TranslatorRuntimeState> = _runtimeState.asStateFlow()

    val current: TranslatorRuntimeState
        get() = _runtimeState.value

    fun setAccessibilityActive(active: Boolean) {
        _runtimeState.update { it.copy(isAccessibilityActive = active) }
    }

    fun setTranslatingActive(active: Boolean) {
        _runtimeState.update { it.copy(isTranslatingActive = active) }
    }

    fun setBubbleRunning(running: Boolean) {
        _runtimeState.update { it.copy(isBubbleRunning = running) }
    }

    fun setNotificationActive(active: Boolean) {
        _runtimeState.update { it.copy(isNotificationActive = active) }
    }

    fun toggleTranslating(): Boolean {
        var next = false
        _runtimeState.update { state ->
            next = !state.isTranslatingActive
            state.copy(isTranslatingActive = next)
        }
        return next
    }

    fun stopAllSurfaces() {
        _runtimeState.update {
            it.copy(
                isTranslatingActive = false,
                isBubbleRunning = false,
                isNotificationActive = false
            )
        }
    }

    fun disconnectAccessibility() {
        _runtimeState.value = TranslatorRuntimeState()
    }
}

