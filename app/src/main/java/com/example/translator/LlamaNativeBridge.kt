package com.example.translator

import android.util.Log

/**
 * Puente nativo JNI para la biblioteca C++ llama.cpp en arquitecturas ARM64.
 *
 * Por qué: Permite la ejecución de modelos GGUF mapeados en memoria (mmap)
 * directamente en la CPU/GPU del dispositivo móvil con aceleración ARM NEON,
 * logrando inferencia 100% offline sin fugas de memoria en la máquina virtual ART.
 */
object LlamaNativeBridge {

    private const val TAG = "LlamaNativeBridge"
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("llama")
            isLibraryLoaded = true
            Log.i(TAG, "libllama.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            Log.w(TAG, "libllama.so is not available on this build architecture")
        } catch (e: Exception) {
            isLibraryLoaded = false
            Log.e(TAG, "Unexpected error loading libllama.so", e)
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    external fun nativeInitModel(modelPath: String, threads: Int, contextSize: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    external fun nativeFreeModel(handle: Long)
}

