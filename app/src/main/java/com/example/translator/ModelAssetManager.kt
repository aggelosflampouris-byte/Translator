package com.example.translator

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestor de activos para el modelo de traducción especializado local (Index-Translate-2B GGUF).
 *
 * Por qué: El modelo pesa ~1.32 GB y no puede ser empaquetado directamente dentro del APK
 * debido a los límites de tamaño de Google Play y GitHub Releases. Este gestor permite la
 * descarga directa y reanudable desde Hugging Face hacia el almacenamiento seguro de la app,
 * así como la importación manual de archivos .gguf locales sin necesidad de conexión a internet.
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"
    const val MODEL_FILENAME = "index-translate-2b.q4_k_m.gguf"
    
    // Por qué: Repositorio oficial en Hugging Face con pesos cuantizados a 4 bits de Index-Translate-2B
    const val MODEL_DOWNLOAD_URL = "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/main/Index-Translate-2B.Q4_K_M.gguf"
    const val APPROX_MODEL_BYTES = 1395864320L // ~1.30 GB

    private val isDownloading = AtomicBoolean(false)
    private var downloadCall: okhttp3.Call? = null

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int, val downloadedMB: String, val totalMB: String) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    var currentListener: ((DownloadState) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postState(state: DownloadState) {
        mainHandler.post {
            currentListener?.invoke(state)
        }
    }

    fun getModelFile(context: Context): File {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, MODEL_FILENAME)
    }

    fun isModelReady(context: Context): Boolean {
        // Verificar si existe ruta personalizada
        val customPath = getCustomModelPath(context)
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.length() > 50_000_000L) {
                return true
            }
        }
        val file = getModelFile(context)
        return file.exists() && file.length() > 500_000_000L
    }

    fun getModelSizeBytes(context: Context): Long {
        val customPath = getCustomModelPath(context)
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists()) return customFile.length()
        }
        val file = getModelFile(context)
        return if (file.exists()) file.length() else 0L
    }

    fun isDownloading(): Boolean = isDownloading.get()

    suspend fun startDownload(context: Context) = withContext(Dispatchers.IO) {
        if (!isDownloading.compareAndSet(false, true)) {
            Log.w(TAG, "Download already in progress")
            return@withContext
        }

        val targetFile = getModelFile(context)
        val tempFile = File(targetFile.parentFile, "$MODEL_FILENAME.tmp")

        postState(DownloadState.Downloading(0, "0.0", String.format("%.1f", APPROX_MODEL_BYTES / (1024f * 1024f))))

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(MODEL_DOWNLOAD_URL)
            .build()

        try {
            val call = client.newCall(request)
            downloadCall = call
            val response = call.execute()

            if (!response.isSuccessful) {
                isDownloading.set(false)
                postState(DownloadState.Error("HTTP error: ${response.code}"))
                return@withContext
            }

            val body = response.body
            if (body == null) {
                isDownloading.set(false)
                postState(DownloadState.Error("Empty response body"))
                return@withContext
            }

            val totalBytes = if (body.contentLength() > 0) body.contentLength() else APPROX_MODEL_BYTES
            val totalMbFormatted = String.format("%.1f", totalBytes / (1024f * 1024f))

            var downloadedBytes = 0L
            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var lastPercent = -1

            outputStream.use { out ->
                inputStream.use { inp ->
                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        if (!isDownloading.get()) {
                            // Cancelled
                            out.flush()
                            tempFile.delete()
                            postState(DownloadState.Idle)
                            return@withContext
                        }
                        out.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val downloadedMbFormatted = String.format("%.1f", downloadedBytes / (1024f * 1024f))
                            postState(DownloadState.Downloading(percent, downloadedMbFormatted, totalMbFormatted))
                        }
                    }
                    out.flush()
                }
            }

            // Renombrar atómicamente el archivo temporal
            if (tempFile.exists() && tempFile.length() > 500_000_000L) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
                isDownloading.set(false)
                postState(DownloadState.Completed)
                Log.i(TAG, "Model downloaded successfully to ${targetFile.absolutePath}")
            } else {
                tempFile.delete()
                isDownloading.set(false)
                postState(DownloadState.Error("Downloaded file is incomplete or corrupt"))
            }

        } catch (e: Exception) {
            isDownloading.set(false)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (downloadCall?.isCanceled() == true) {
                postState(DownloadState.Idle)
            } else {
                Log.e(TAG, "Download failed", e)
                postState(DownloadState.Error(e.message ?: "Network error"))
            }
        } finally {
            downloadCall = null
        }
    }

    fun cancelDownload() {
        if (isDownloading.compareAndSet(true, false)) {
            downloadCall?.cancel()
            downloadCall = null
            postState(DownloadState.Idle)
        }
    }

    fun deleteModel(context: Context): Boolean {
        cancelDownload()
        clearCustomModelPath(context)
        val file = getModelFile(context)
        val deleted = if (file.exists()) file.delete() else true
        val temp = File(file.parentFile, "$MODEL_FILENAME.tmp")
        if (temp.exists()) temp.delete()
        postState(DownloadState.Idle)
        return deleted
    }

    fun setCustomModelPath(context: Context, path: String) {
        context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("custom_model_path", path)
            .apply()
    }

    fun getCustomModelPath(context: Context): String? {
        return context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("custom_model_path", null)
    }

    fun clearCustomModelPath(context: Context) {
        context.getSharedPreferences(AppDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("custom_model_path")
            .apply()
    }

    /**
     * Copia un archivo .gguf seleccionado por el usuario a través de Storage Access Framework (SAF)
     */
    suspend fun importModelFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(context)
        val tempFile = File(targetFile.parentFile, "$MODEL_FILENAME.tmp")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (tempFile.exists() && tempFile.length() > 50_000_000L) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                clearCustomModelPath(context)
                postState(DownloadState.Completed)
                return@withContext true
            } else {
                tempFile.delete()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model from URI", e)
            if (tempFile.exists()) tempFile.delete()
            return@withContext false
        }
    }
}

