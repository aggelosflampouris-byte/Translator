package com.example.translator

/**
 * Firma de posición del último evento de desplazamiento para detectar reenvíos de layout sin movimiento.
 */
data class ScrollPositionSignature(
    val windowId: Int,
    val scrollY: Int,
    val fromIndex: Int,
    val toIndex: Int
)

/**
 * Decisión sobre si un evento `TYPE_VIEW_SCROLLED` corresponde a un desplazamiento vertical real del chat.
 */
data class ScrollDecision(
    val isRelevantVerticalScroll: Boolean,
    val reason: String,
    val nextSignature: ScrollPositionSignature?
)

/**
 * Filtra eventos `TYPE_VIEW_SCROLLED` horizontales, de subcomponentes pequeños o sin desplazamiento real
 * (como carruseles Litho, marquesinas de texto o relayouts en reposo) que bloquearían el escaneo.
 * Por qué: Apps como Messenger emiten eventos de scroll internos aun con la conversación quieta.
 */
object ScrollEventClassifier {

    fun classify(
        hasDeltaApi: Boolean,
        scrollDeltaX: Int,
        scrollDeltaY: Int,
        sourceHeightPx: Int,
        screenHeightPx: Int,
        windowId: Int,
        scrollY: Int,
        maxScrollY: Int,
        fromIndex: Int,
        toIndex: Int,
        itemCount: Int,
        prevSignature: ScrollPositionSignature?
    ): ScrollDecision {
        // 1. Subvistas pequeñas (bandejas horizontales, barras, temporizadores) no mueven los mensajes del chat
        if (sourceHeightPx > 0 && screenHeightPx > 0 && sourceHeightPx < screenHeightPx / 4) {
            return ScrollDecision(false, "small_view(${sourceHeightPx}px)", prevSignature)
        }

        val currentSig = ScrollPositionSignature(windowId, scrollY, fromIndex, toIndex)

        if (hasDeltaApi) {
            if (scrollDeltaY != 0) {
                return ScrollDecision(true, "dy=$scrollDeltaY", currentSig)
            }
            if (scrollDeltaX != 0) {
                return ScrollDecision(false, "horiz_only(dx=$scrollDeltaX)", prevSignature)
            }
            // Por qué: En API 28+, deltaX=0 y deltaY=0 suele ser un re-cálculo de layout en reposo;
            // solo lo aceptamos si la posición/rango visible cambió respecto al evento anterior.
            val hasMeasurablePos = scrollY > 0 || maxScrollY > 0 || fromIndex >= 0 || toIndex >= 0 || itemCount > 0
            if (!hasMeasurablePos) {
                return ScrollDecision(false, "empty_zero_delta", prevSignature)
            }
            if (prevSignature != null && prevSignature == currentSig) {
                return ScrollDecision(false, "zero_delta_repeat", prevSignature)
            }
            return ScrollDecision(true, "pos_changed($scrollY,$fromIndex..$toIndex)", currentSig)
        }

        if (prevSignature != null && prevSignature == currentSig) {
            return ScrollDecision(false, "legacy_repeat", prevSignature)
        }
        return ScrollDecision(true, "legacy_scroll($scrollY,$fromIndex..$toIndex)", currentSig)
    }
}

