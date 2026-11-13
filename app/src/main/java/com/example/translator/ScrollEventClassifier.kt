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
    val nextSignature: ScrollPositionSignature?,
    val shouldInterruptOverlays: Boolean = isRelevantVerticalScroll
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
            return ScrollDecision(false, "small_view(${sourceHeightPx}px)", prevSignature, shouldInterruptOverlays = false)
        }

        val currentSig = ScrollPositionSignature(windowId, scrollY, fromIndex, toIndex)

        if (hasDeltaApi) {
            if (scrollDeltaY != 0) {
                return ScrollDecision(true, "dy=$scrollDeltaY", currentSig, shouldInterruptOverlays = true)
            }
            if (scrollDeltaX != 0) {
                return ScrollDecision(false, "horiz_only(dx=$scrollDeltaX)", prevSignature, shouldInterruptOverlays = false)
            }
            // Por qué: En API 28+, deltaX=0 y deltaY=0 suele ser un re-cálculo de layout en reposo o inserción
            // de un nuevo mensaje; evitar borrar burbujas ni abortar escaneos cuando el desplazamiento es nulo o mínimo.
            val hasMeasurablePos = scrollY > 0 || maxScrollY > 0 || fromIndex >= 0 || toIndex >= 0 || itemCount > 0
            if (!hasMeasurablePos) {
                return ScrollDecision(false, "empty_zero_delta", prevSignature, shouldInterruptOverlays = false)
            }
            if (prevSignature != null && prevSignature == currentSig) {
                return ScrollDecision(false, "zero_delta_repeat", prevSignature, shouldInterruptOverlays = false)
            }
            if (prevSignature != null &&
                prevSignature.windowId == currentSig.windowId &&
                scrollY == prevSignature.scrollY &&
                fromIndex >= 0 && prevSignature.fromIndex >= 0 &&
                kotlin.math.abs(fromIndex - prevSignature.fromIndex) <= 1 &&
                kotlin.math.abs(toIndex - prevSignature.toIndex) <= 1
            ) {
                return ScrollDecision(false, "layout_jitter($fromIndex..$toIndex)", currentSig, shouldInterruptOverlays = false)
            }
            val isLargeJump = prevSignature != null && (
                kotlin.math.abs(scrollY - prevSignature.scrollY) >= 80 ||
                    (fromIndex >= 0 && prevSignature.fromIndex >= 0 && kotlin.math.abs(fromIndex - prevSignature.fromIndex) >= 3)
                )
            return ScrollDecision(
                isRelevantVerticalScroll = true,
                reason = "pos_changed($scrollY,$fromIndex..$toIndex)",
                nextSignature = currentSig,
                shouldInterruptOverlays = isLargeJump
            )
        }

        if (prevSignature != null && prevSignature == currentSig) {
            return ScrollDecision(false, "legacy_repeat", prevSignature, shouldInterruptOverlays = false)
        }
        return ScrollDecision(true, "legacy_scroll($scrollY,$fromIndex..$toIndex)", currentSig, shouldInterruptOverlays = true)
    }
}

