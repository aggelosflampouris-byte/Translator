package com.example.translator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Agrupa entregas asíncronas de traducción dentro de una ventana corta para pintar burbujas en lote
 * sin efecto "palomitas" y manteniendo latencia cero cuando llega el último elemento del lote.
 *
 * Por qué: Publicar cada resultado individual de ML Kit en un `post` distinto multiplica los recorridos
 * de layout y expulsiones por colisión en el hilo principal.
 */
class OverlayDeliveryCoalescer<T>(
    private val scope: CoroutineScope,
    private val windowMs: Long = 40L,
    private val onFlush: (batchId: Long, items: List<T>) -> Unit
) {
    private val lock = Any()
    private var activeBatchId: Long = -1L
    private var remainingGroups: Int = 0
    private val buffer = ArrayList<T>()
    private var flushJob: Job? = null

    fun reset(newBatchId: Long, expectedPendingGroups: Int) {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            activeBatchId = newBatchId
            remainingGroups = expectedPendingGroups.coerceAtLeast(0)
            buffer.clear()
        }
    }

    fun enqueue(batchId: Long, items: List<T>, completedGroup: Boolean = true) {
        var immediateItems: List<T>? = null
        synchronized(lock) {
            if (batchId != activeBatchId) return
            buffer.addAll(items)
            if (completedGroup && remainingGroups > 0) {
                remainingGroups--
            }
            if (remainingGroups == 0) {
                flushJob?.cancel()
                flushJob = null
                if (buffer.isNotEmpty()) {
                    immediateItems = buffer.toList()
                    buffer.clear()
                }
            } else if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(windowMs)
                    flush(batchId)
                }
            }
        }
        val toEmit = immediateItems ?: return
        onFlush(batchId, toEmit)
    }

    fun markGroupDoneEmpty(batchId: Long) {
        var immediateItems: List<T>? = null
        synchronized(lock) {
            if (batchId != activeBatchId) return
            if (remainingGroups > 0) {
                remainingGroups--
            }
            if (remainingGroups == 0) {
                flushJob?.cancel()
                flushJob = null
                if (buffer.isNotEmpty()) {
                    immediateItems = buffer.toList()
                    buffer.clear()
                }
            }
        }
        val toEmit = immediateItems ?: return
        onFlush(batchId, toEmit)
    }

    fun isBatchComplete(batchId: Long): Boolean {
        synchronized(lock) {
            return batchId == activeBatchId && remainingGroups == 0 && buffer.isEmpty()
        }
    }

    fun cancel() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            activeBatchId = -1L
            remainingGroups = 0
            buffer.clear()
        }
    }

    private fun flush(targetBatchId: Long) {
        val toEmit: List<T>
        synchronized(lock) {
            if (targetBatchId != activeBatchId || buffer.isEmpty()) {
                flushJob = null
                return
            }
            toEmit = buffer.toList()
            buffer.clear()
            flushJob = null
        }
        onFlush(targetBatchId, toEmit)
    }
}

