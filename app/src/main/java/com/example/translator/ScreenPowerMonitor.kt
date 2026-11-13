package com.example.translator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Supervisa el estado de pantalla apagada/desbloqueada e implementa la pausa automática por inactividad.
 *
 * Por qué: Mantiene al servicio de accesibilidad sin despertares mientras la pantalla está apagada y
 * desacopla el BroadcastReceiver del orquestador principal.
 */
class ScreenPowerMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val isTranslatingActive: () -> Boolean,
    private val onScreenOff: () -> Unit,
    private val onScreenInteractive: () -> Unit,
    private val onIdleAutoPauseTriggered: (reason: String) -> Unit
) {
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile var isScreenInteractive: Boolean = powerManager?.isInteractive != false
        private set

    @Volatile var idleAutoPauseMinutes: Int = AppDefaults.DEFAULT_IDLE_AUTO_PAUSE_MINUTES
    @Volatile var lastTargetInteractionMs: Long = SystemClock.uptimeMillis()

    private var idlePauseJob: Job? = null
    private var receiverRegistered: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    fun register(initialIdleMinutes: Int) {
        idleAutoPauseMinutes = initialIdleMinutes
        lastTargetInteractionMs = SystemClock.uptimeMillis()
        isScreenInteractive = powerManager?.isInteractive != false
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    fun unregister() {
        cancelIdlePauseTimer()
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Ignorar si ya fue desregistrado
        }
        receiverRegistered = false
    }

    fun isInteractiveNow(): Boolean = isScreenInteractive && (powerManager?.isInteractive != false)

    fun markTargetInteraction(nowMs: Long = SystemClock.uptimeMillis()) {
        lastTargetInteractionMs = nowMs
    }

    fun cancelIdlePauseTimer() {
        idlePauseJob?.cancel()
        idlePauseJob = null
    }

    fun onIdleMinutesUpdated(minutes: Int) {
        idleAutoPauseMinutes = minutes
        if (minutes <= 0) {
            cancelIdlePauseTimer()
        } else if (!isScreenInteractive && isTranslatingActive()) {
            scheduleIdleAutoPauseAfterScreenOff()
        }
    }

    fun shouldTriggerIdleAutoPause(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val limitMinutes = idleAutoPauseMinutes
        if (limitMinutes <= 0 || !isTranslatingActive()) return false
        return (nowMs - lastTargetInteractionMs) >= limitMinutes * 60_000L
    }

    fun scheduleIdleAutoPauseAfterScreenOff() {
        cancelIdlePauseTimer()
        val limitMinutes = idleAutoPauseMinutes
        if (limitMinutes <= 0 || !isTranslatingActive()) return
        val limitMs = limitMinutes * 60_000L
        val elapsedMs = (SystemClock.uptimeMillis() - lastTargetInteractionMs).coerceAtLeast(0L)
        val remainingMs = (limitMs - elapsedMs).coerceAtLeast(0L)
        idlePauseJob = scope.launch(workerDispatcher) {
            if (remainingMs > 0L) delay(remainingMs)
            if (isTranslatingActive() && !isInteractiveNow()) {
                onIdleAutoPauseTriggered("idle_scroff")
            }
        }
    }

    private fun handleScreenOff() {
        isScreenInteractive = false
        onScreenOff()
        if (isTranslatingActive()) {
            scheduleIdleAutoPauseAfterScreenOff()
        }
    }

    private fun handleScreenOn() {
        isScreenInteractive = true
        cancelIdlePauseTimer()
        onScreenInteractive()
    }
}

