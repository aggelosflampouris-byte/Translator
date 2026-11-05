package com.example.translator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Gestiona la verificación de versiones en GitHub y la descarga e instalación segura de APK.
 *
 * Propiedades de seguridad implementadas y por qué:
 * 1. Sin UI interna: [checkForUpdates] devuelve [UpdateInfo] y la Activity que lo invoca
 *    gestiona el diálogo. Por qué: evita caídas por tokens de ventana no válidos al intentar
 *    crear un AlertDialog desde contextos que no son Activity.
 * 2. Ciclo de vida seguro del BroadcastReceiver: se desregistra siempre tras recibir el evento
 *    o mediante un temporizador de seguridad de 90 segundos, y usa RECEIVER_NOT_EXPORTED.
 *    Por qué: evita fugas de memoria del sistema y llamadas no autorizadas desde apps externas.
 * 3. Verificación de integridad SHA-256: se compara el hash del APK contra el `.apk.sha256`
 *    publicado en la release antes de solicitar la instalación al sistema.
 *
 * @param appContext Application context (nunca una Activity ni Service para evitar fugas).
 */
class AppUpdater(private val appContext: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val repoUrl =
        "https://api.github.com/repos/aggelosflampouris-byte/Translator/releases/latest"

    // Receiver lifecycle — both nulled out after each download attempt.
    private var pendingReceiver: BroadcastReceiver? = null
    private var receiverTimeoutRunnable: Runnable? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetches the latest GitHub release. Returns [UpdateInfo] when a newer version is
     * available, or `null` when the app is up-to-date or the check fails.
     *
     * Must be called from a coroutine; performs network I/O on [Dispatchers.IO].
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(repoUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w("AppUpdater", "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = gson.fromJson(body, GitHubRelease::class.java)

            val latestVersion = release.tag_name
            val currentVersion = "v${BuildConfig.VERSION_NAME}"

            if (latestVersion == currentVersion) {
                Log.d("AppUpdater", "App is up to date ($currentVersion).")
                return@withContext null
            }

            // Find the APK asset and its companion SHA-256 checksum asset.
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: run {
                    Log.w("AppUpdater", "No APK asset found in release $latestVersion.")
                    return@withContext null
                }

            val shaAsset = release.assets.firstOrNull { it.name == "${apkAsset.name}.sha256" }

            if (shaAsset == null) {
                Log.w("AppUpdater", "No .sha256 asset found for ${apkAsset.name}. Skipping update.")
                return@withContext null
            }

            UpdateInfo(
                version = latestVersion,
                apkUrl = apkAsset.browser_download_url,
                shaUrl = shaAsset.browser_download_url
            )
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to check for updates", e)
            null
        }
    }

    /**
     * Initiates the download of the APK described by [updateInfo].
     * After download, verifies the SHA-256 checksum before installing.
     *
     * Must be called with an **Activity** context so that the system installer
     * can attach to a window token. The [appContext] held by this class is
     * used for [DownloadManager] only — never for showing UI.
     *
     * @param activityContext An Activity (or Composable LocalContext) — required for install intent.
     * @param updateInfo      Result from [checkForUpdates].
     * @param onError         Called on the Main thread if the download or verification fails.
     */
    fun downloadAndInstall(
        activityContext: Context,
        updateInfo: UpdateInfo,
        onError: (String) -> Unit
    ) {
        Toast.makeText(appContext, "Downloading update ${updateInfo.version}…", Toast.LENGTH_SHORT).show()

        val destination = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "Translator-${updateInfo.version}.apk"
        )
        if (destination.exists()) destination.delete()

        val dmRequest = DownloadManager.Request(Uri.parse(updateInfo.apkUrl))
            .setTitle("Translator Update")
            .setDescription("Downloading ${updateInfo.version}")
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                destination.name
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(dmRequest)

        // Por qué: Si la descarga nunca termina o se congela, liberamos el receiver
        // tras 90 segundos para evitar fugas de memoria en el sistema.
        val timeoutRunnable = Runnable {
            Log.w("AppUpdater", "Download timed out — unregistering receiver.")
            unregisterPendingReceiver()
            handler.post { onError("Download timed out. Please try again.") }
        }
        receiverTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, DOWNLOAD_TIMEOUT_MS)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return // Ignorar descargas de otras apps

                // Por qué: Desregistrar inmediatamente al terminar para prevenir fugas
                cancelTimeout()
                unregisterPendingReceiver()

                // Verificación de suma de comprobación fuera del hilo principal
                Thread {
                    verifyAndInstall(destination, updateInfo.shaUrl, activityContext, onError)
                }.start()
            }
        }
        pendingReceiver = receiver

        // Por qué RECEIVER_NOT_EXPORTED: El DownloadManager del sistema envía broadcasts
        // internos; ninguna app externa debe tener acceso a activar este receiver.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Verifies the downloaded APK's SHA-256 against the expected checksum fetched from
     * [shaUrl], then installs if they match. Runs on a background thread.
     */
    private fun verifyAndInstall(
        file: File,
        shaUrl: String,
        activityContext: Context,
        onError: (String) -> Unit
    ) {
        try {
            if (!file.exists()) {
                handler.post { onError("Downloaded file not found.") }
                return
            }

            // 1. Fetch the expected hash from GitHub (tiny plaintext file).
            val expectedHash = fetchExpectedHash(shaUrl)
            if (expectedHash == null) {
                file.delete()
                handler.post { onError("Could not fetch integrity checksum. Update aborted.") }
                return
            }

            // 2. Compute the actual SHA-256 of the downloaded file.
            val actualHash = computeSha256(file)

            // 3. Compare. Abort and delete on mismatch.
            if (!actualHash.equals(expectedHash.trim(), ignoreCase = true)) {
                Log.e("AppUpdater", "SHA-256 mismatch! expected=$expectedHash actual=$actualHash")
                file.delete()
                handler.post {
                    onError("Integrity check failed — update file may be corrupted or tampered. Aborted.")
                }
                return
            }

            Log.d("AppUpdater", "SHA-256 verified ✓ ($actualHash)")

            // 4. Install on Main thread (requires window token from Activity).
            handler.post { installApk(file, activityContext) }

        } catch (e: Exception) {
            Log.e("AppUpdater", "Verification failed", e)
            file.delete()
            handler.post { onError("Update verification error: ${e.message}") }
        }
    }

    /** Downloads the .sha256 file content (a 64-char hex string) via OkHttp. */
    private fun fetchExpectedHash(shaUrl: String): String? {
        return try {
            val request = Request.Builder().url(shaUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("AppUpdater", "Failed to fetch .sha256 file: ${response.code}")
                return null
            }
            response.body?.string()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.lowercase()
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error fetching SHA-256 checksum", e)
            null
        }
    }

    /** Computes the hex-encoded SHA-256 digest of [file]. */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installApk(file: File, activityContext: Context) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdater", "Failed to launch installer", e)
            Toast.makeText(appContext, "Failed to start installation", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unregisterPendingReceiver() {
        pendingReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — safe to ignore.
            }
            pendingReceiver = null
        }
    }

    private fun cancelTimeout() {
        receiverTimeoutRunnable?.let { handler.removeCallbacks(it) }
        receiverTimeoutRunnable = null
    }

    // ── Data classes ───────────────────────────────────────────────────────────

    /** Returned by [checkForUpdates] when a newer version is available. */
    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val shaUrl: String
    )

    data class GitHubRelease(val tag_name: String, val assets: List<Asset>)
    data class Asset(val name: String, val browser_download_url: String)

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 90_000L
    }
}
